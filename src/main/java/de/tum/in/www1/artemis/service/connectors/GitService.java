package de.tum.in.www1.artemis.service.connectors;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.annotation.Nullable;
import javax.annotation.PostConstruct;
import javax.validation.constraints.NotNull;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.HiddenFileFilter;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.filter.CommitTimeRevFilter;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.transport.sshd.JGitKeyCache;
import org.eclipse.jgit.transport.sshd.KeyPasswordProvider;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.tum.in.www1.artemis.domain.*;
import de.tum.in.www1.artemis.domain.participation.ProgrammingExerciseParticipation;
import de.tum.in.www1.artemis.domain.participation.ProgrammingExerciseStudentParticipation;
import de.tum.in.www1.artemis.domain.participation.StudentParticipation;
import de.tum.in.www1.artemis.exception.GitException;
import de.tum.in.www1.artemis.service.ZipFileService;
import de.tum.in.www1.artemis.web.rest.errors.EntityNotFoundException;

@Service
public class GitService {

    private final Logger log = LoggerFactory.getLogger(GitService.class);

    @Value("${artemis.version-control.url}")
    private URL gitUrl;

    @Value("${artemis.version-control.user}")
    private String gitUser;

    @Value("${artemis.version-control.password}")
    private String gitPassword;

    @Value("${artemis.version-control.token:#{null}}")
    private Optional<String> gitToken;

    @Value("${artemis.version-control.ssh-private-key-folder-path:#{null}}")
    private Optional<String> gitSshPrivateKeyPath;

    @Value("${artemis.version-control.ssh-private-key-password:#{null}}")
    private Optional<String> gitSshPrivateKeyPassphrase;

    @Value("${artemis.version-control.ssh-template-clone-url:#{null}}")
    private Optional<String> sshUrlTemplate;

    @Value("${artemis.repo-clone-path}")
    private String repoClonePath;

    @Value("${artemis.git.name}")
    private String artemisGitName;

    @Value("${artemis.git.email}")
    private String artemisGitEmail;

    private final Map<Path, Repository> cachedRepositories = new ConcurrentHashMap<>();

    private final Map<Path, Path> cloneInProgressOperations = new ConcurrentHashMap<>();

    private final ZipFileService zipFileService;

    private TransportConfigCallback sshCallback;

    private static final int JGIT_TIMEOUT_IN_SECONDS = 5;

    public GitService(ZipFileService zipFileService) {
        log.info("file.encoding={}", System.getProperty("file.encoding"));
        log.info("sun.jnu.encoding={}", System.getProperty("sun.jnu.encoding"));
        log.info("Default Charset={}", Charset.defaultCharset());
        log.info("Default Charset in Use={}", new OutputStreamWriter(new ByteArrayOutputStream()).getEncoding());
        this.zipFileService = zipFileService;
    }

    /**
     * initialize the GitService, in particular which authentication mechanism should be used
     * Artemis uses the following order for authentication:
     * 1. ssh key (if available)
     * 2. username + personal access token (if available)
     * 3. username + password
     */
    @PostConstruct
    public void init() {
        if (useSsh()) {
            log.info("GitService will use ssh keys as authentication method to interact with remote git repositories");
            configureSsh();
        }
        else if (gitToken.isPresent()) {
            log.info("GitService will use username + token as authentication method to interact with remote git repositories");
            CredentialsProvider.setDefault(new UsernamePasswordCredentialsProvider(gitUser, gitToken.get()));
        }
        else {
            log.info("GitService will use username + password as authentication method to interact with remote git repositories");
            CredentialsProvider.setDefault(new UsernamePasswordCredentialsProvider(gitUser, gitPassword));
        }
    }

    private void configureSsh() {

        var credentialsProvider = new CredentialsProvider() {

            @Override
            public boolean isInteractive() {
                return false;
            }

            @Override
            public boolean supports(CredentialItem... items) {
                return true;
            }

            // Note: the following method allows us to store known hosts
            @Override
            public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
                for (CredentialItem item : items) {
                    if (item instanceof CredentialItem.YesNoType) {
                        ((CredentialItem.YesNoType) item).setValue(true);
                    }
                }
                return true;
            }
        };

        CredentialsProvider.setDefault(credentialsProvider);

        var sshSessionFactory = new SshdSessionFactoryBuilder().setKeyPasswordProvider(keyPasswordProvider -> new KeyPasswordProvider() {

            @Override
            public char[] getPassphrase(URIish uri, int attempt) {
                log.debug("getPassphrase: {}, attempt: {}", uri.toString(), attempt);
                // Example: /Users/artemis/.ssh/artemis/id_rsa contains /Users/artemis/.ssh/artemis
                if (gitSshPrivateKeyPath.isPresent() && uri.getPath().contains(gitSshPrivateKeyPath.get())) {
                    return gitSshPrivateKeyPassphrase.get().toCharArray();
                }
                else {
                    return null;
                }
            }

            @Override
            public void setAttempts(int maxNumberOfAttempts) {
            }

            @Override
            public boolean keyLoaded(URIish uri, int attempt, Exception error) {
                return false;
            }
        }).setConfigStoreFactory((homeDir, configFile, localUserName) -> (hostName, port, userName) -> new SshConfigStore.HostConfig() {

            @Override
            public String getValue(String key) {
                return null;
            }

            @Override
            public List<String> getValues(String key) {
                return Collections.emptyList();
            }

            @Override
            // NOTE: this is some kind of workaround to only avoid host checking for the git server that we use
            // this simplifies administration and should be secure, because the known hosts file does not need to be created
            public Map<String, String> getOptions() {
                log.debug("getOptions: {}:{}", hostName, port);
                if (hostName.equals(gitUrl.getHost())) {
                    return Collections.singletonMap(SshConstants.STRICT_HOST_KEY_CHECKING, SshConstants.NO);
                }
                else {
                    return Collections.emptyMap();
                }
            }

            @Override
            public Map<String, List<String>> getMultiValuedOptions() {
                return Collections.emptyMap();
            }
        }).setSshDirectory(new java.io.File(gitSshPrivateKeyPath.get())).setHomeDirectory(new java.io.File(System.getProperty("user.home"))).build(new JGitKeyCache());

        sshCallback = transport -> {
            if (transport instanceof SshTransport) {
                SshTransport sshTransport = (SshTransport) transport;
                transport.setTimeout(JGIT_TIMEOUT_IN_SECONDS);
                sshTransport.setSshSessionFactory(sshSessionFactory);
            }
            else {
                log.error("Cannot use ssh properly because of mismatch of Jgit transport object: {}", transport);
            }
        };
    }

    private boolean useSsh() {
        return gitSshPrivateKeyPath.isPresent() && sshUrlTemplate.isPresent();
        // password is optional and will only be applied if the ssh private key was encrypted using a password
    }

    private String getGitUriAsString(VcsRepositoryUrl vcsRepositoryUrl) throws URISyntaxException {
        return getGitUri(vcsRepositoryUrl).toString();
    }

    private URI getGitUri(VcsRepositoryUrl vcsRepositoryUrl) throws URISyntaxException {
        return useSsh() ? getSshUri(vcsRepositoryUrl) : vcsRepositoryUrl.getURL().toURI();
    }

    private URI getSshUri(VcsRepositoryUrl vcsRepositoryUrl) throws URISyntaxException {
        URI templateUri = new URI(sshUrlTemplate.get());
        // Example Bitbucket: ssh://git@bitbucket.ase.in.tum.de:7999/se2021w07h02/se2021w07h02-ga27yox.git
        // Example Gitlab: ssh://git@gitlab.ase.in.tum.de:2222/se2021w07h02/se2021w07h02-ga27yox.git
        final var repositoryUri = vcsRepositoryUrl.getURL().toURI();
        // Bitbucket repository urls (until now mainly used with username and password authentication) include "/scm" in the url, which cannot be used in ssh urls,
        // therefore we need to replace it here
        final var path = repositoryUri.getPath().replace("/scm", "");
        return new URI(templateUri.getScheme(), templateUri.getUserInfo(), templateUri.getHost(), templateUri.getPort(), path, null, repositoryUri.getFragment());
    }

    /**
     * Get the local repository for a given participation. If the local repo does not exist yet, it will be checked out.
     * Saves the local repo in the default path.
     *
     * @param participation Participation the remote repository belongs to.
     * @return the repository if it could be checked out
     * @throws InterruptedException if the repository could not be checked out.
     * @throws GitAPIException      if the repository could not be checked out.
     */
    public Repository getOrCheckoutRepository(ProgrammingExerciseParticipation participation) throws InterruptedException, GitAPIException {
        return getOrCheckoutRepository(participation, repoClonePath);
    }

    /**
     * Get the local repository for a given participation. If the local repo does not exist yet, it will be checked out.
     * Saves the local repo in the default path.
     *
     * @param participation Participation the remote repository belongs to.
     * @param targetPath    path where the repo is located on disk
     * @return the repository if it could be checked out
     * @throws InterruptedException if the repository could not be checked out.
     * @throws GitAPIException      if the repository could not be checked out.
     */
    public Repository getOrCheckoutRepository(ProgrammingExerciseParticipation participation, String targetPath) throws InterruptedException, GitAPIException {
        var repoUrl = participation.getVcsRepositoryUrl();
        Repository repository = getOrCheckoutRepository(repoUrl, targetPath, true);
        repository.setParticipation(participation);
        return repository;
    }

    /**
     * Get the local repository for a given participation.
     * If the local repo does not exist yet, it will be checked out.
     * <p>
     * This method will include the participation ID in the local path of the repository so
     * JPlag can refer back to the correct participation.
     *
     * @param participation Participation the remote repository belongs to.
     * @param targetPath    path where the repo is located on disk
     * @return the repository if it could be checked out
     * @throws InterruptedException if the repository could not be checked out.
     * @throws GitAPIException      if the repository could not be checked out.
     */
    public Repository getOrCheckoutRepositoryForJPlag(ProgrammingExerciseParticipation participation, String targetPath) throws InterruptedException, GitAPIException {
        var repoUrl = participation.getVcsRepositoryUrl();
        String repoFolderName = folderNameForRepositoryUrl(repoUrl);

        // Replace the exercise name in the repository folder name with the participation ID.
        // This is necessary to be able to refer back to the correct participation after the JPlag detection run.
        String updatedRepoFolderName = repoFolderName.replaceAll("/[a-zA-Z0-9]*-", "/" + participation.getId() + "-");
        Path localPath = Paths.get(targetPath, updatedRepoFolderName);

        Repository repository = getOrCheckoutRepository(repoUrl, localPath, true);
        repository.setParticipation(participation);

        return repository;
    }

    /**
     * Get the local repository for a given remote repository URL. If the local repo does not exist yet, it will be checked out.
     * Saves the repo in the default path
     *
     * @param repoUrl   The remote repository.
     * @param pullOnGet Pull from the remote on the checked out repository, if it does not need to be cloned.
     * @return the repository if it could be checked out.
     * @throws InterruptedException if the repository could not be checked out.
     * @throws GitAPIException      if the repository could not be checked out.
     */
    public Repository getOrCheckoutRepository(VcsRepositoryUrl repoUrl, boolean pullOnGet) throws InterruptedException, GitAPIException {
        return getOrCheckoutRepository(repoUrl, repoClonePath, pullOnGet);
    }

    /**
     * Get the local repository for a given remote repository URL. If the local repo does not exist yet, it will be checked out.
     *
     * @param repoUrl    The remote repository.
     * @param targetPath path where the repo is located on disk
     * @param pullOnGet  Pull from the remote on the checked out repository, if it does not need to be cloned.
     * @return the repository if it could be checked out.
     * @throws InterruptedException if the repository could not be checked out.
     * @throws GitAPIException      if the repository could not be checked out.
     */
    public Repository getOrCheckoutRepository(VcsRepositoryUrl repoUrl, String targetPath, boolean pullOnGet) throws InterruptedException, GitAPIException {
        Path localPath = getLocalPathOfRepo(targetPath, repoUrl);
        return getOrCheckoutRepository(repoUrl, localPath, pullOnGet);
    }

    public Repository getOrCheckoutRepositoryIntoTargetDirectory(VcsRepositoryUrl repoUrl, VcsRepositoryUrl targetUrl, boolean pullOnGet)
            throws InterruptedException, GitAPIException {
        Path localPath = getDefaultLocalPathOfRepo(targetUrl);
        return getOrCheckoutRepository(repoUrl, targetUrl, localPath, pullOnGet);
    }

    public Repository getOrCheckoutRepository(VcsRepositoryUrl repoUrl, Path localPath, boolean pullOnGet) throws InterruptedException, GitAPIException {
        return getOrCheckoutRepository(repoUrl, repoUrl, localPath, pullOnGet);
    }

    /**
     * Get the local repository for a given remote repository URL. If the local repo does not exist yet, it will be checked out.
     *
     * @param sourceRepoUrl The source remote repository.
     * @param targetRepoUrl The target remote repository.
     * @param localPath     The local path to clone the repository to.
     * @param pullOnGet     Pull from the remote on the checked out repository, if it does not need to be cloned.
     * @return the repository if it could be checked out.
     * @throws InterruptedException if the repository could not be checked out.
     * @throws GitAPIException      if the repository could not be checked out.
     */
    public Repository getOrCheckoutRepository(VcsRepositoryUrl sourceRepoUrl, VcsRepositoryUrl targetRepoUrl, Path localPath, boolean pullOnGet)
            throws InterruptedException, GitAPIException {
        // First try to just retrieve the git repository from our server, as it might already be checked out.
        // If the sourceRepoUrl differs from the targetRepoUrl, we attempt to clone the source repo into the target directory
        Repository repository = getExistingCheckedOutRepositoryByLocalPath(localPath, targetRepoUrl);
        // Note: in case the actual git repository in the file system is corrupt (e.g. by accident), we will get an exception here
        // the exception will then delete the folder, so that the next attempt would be successful.
        if (repository != null) {
            if (pullOnGet) {
                pull(repository);
            }
            return repository;
        }
        // If the git repository can't be found on our server, clone it from the remote.
        else {
            int numberOfAttempts = 5;
            // Make sure that multiple clone operations for the same repository cannot happen at the same time.
            while (cloneInProgressOperations.containsKey(localPath)) {
                log.warn("Clone is already in progress. This will lead to an error. Wait for a second");
                Thread.sleep(1000);
                if (numberOfAttempts == 0) {
                    throw new GitException("Cannot clone the same repository multiple times");
                }
                else {
                    numberOfAttempts--;
                }
            }
            // Clone repository.
            try {
                var gitUriAsString = getGitUriAsString(sourceRepoUrl);
                log.debug("Cloning from {} to {}", gitUriAsString, localPath);
                cloneInProgressOperations.put(localPath, localPath);
                // make sure the directory to copy into is empty
                FileUtils.deleteDirectory(localPath.toFile());
                Git result = Git.cloneRepository().setTransportConfigCallback(sshCallback).setURI(gitUriAsString).setDirectory(localPath.toFile()).call();
                result.close();
            }
            catch (GitAPIException | RuntimeException | IOException | URISyntaxException e) {
                log.error("Exception during clone", e);
                // cleanup the folder to avoid problems in the future.
                // 'deleteQuietly' is the same as 'deleteDirectory' but is not throwing an exception, thus we avoid a try-catch block.
                FileUtils.deleteQuietly(localPath.toFile());
                throw new GitException(e);
            }
            finally {
                // make sure that cloneInProgress is released
                cloneInProgressOperations.remove(localPath);
            }
            return getExistingCheckedOutRepositoryByLocalPath(localPath, targetRepoUrl);
        }
    }

    /**
     * Combine all commits of the given repository into one.
     *
     * @param repoUrl of the repository to combine.
     * @throws InterruptedException If the checkout fails
     * @throws GitAPIException      If the checkout fails
     */
    public void combineAllCommitsOfRepositoryIntoOne(VcsRepositoryUrl repoUrl) throws InterruptedException, GitAPIException {
        Repository exerciseRepository = getOrCheckoutRepository(repoUrl, true);
        combineAllCommitsIntoInitialCommit(exerciseRepository);
    }

    public Path getDefaultLocalPathOfRepo(VcsRepositoryUrl targetUrl) {
        return getLocalPathOfRepo(repoClonePath, targetUrl);
    }

    /**
     * Creates a local path by specifying a target path and the target url
     *
     * @param targetPath target directory
     * @param targetUrl  url of the repository
     * @return path of the local file system
     */
    public Path getLocalPathOfRepo(String targetPath, VcsRepositoryUrl targetUrl) {
        return Paths.get(targetPath, folderNameForRepositoryUrl(targetUrl));
    }

    /**
     * Get an existing git repository that is checked out on the server. Returns immediately null if the localPath does not exist. Will first try to retrieve a cached repository
     * from cachedRepositories. Side effect: This method caches retrieved repositories in a HashMap, so continuous retrievals can be avoided (reduces load).
     *
     * @param localPath           to git repo on server.
     * @param remoteRepositoryUrl the remote repository url for the git repository, will be added to the Repository object for later use, can be null
     * @return the git repository in the localPath or **null** if it does not exist on the server.
     */
    public Repository getExistingCheckedOutRepositoryByLocalPath(@NotNull Path localPath, @Nullable VcsRepositoryUrl remoteRepositoryUrl) {
        // Check if there is a folder with the provided path of the git repository.
        if (!Files.exists(localPath)) {
            // In this case we should remove the repository if cached, because it can't exist anymore.
            cachedRepositories.remove(localPath);
            return null;
        }
        // Check if the repository is already cached in the server's session.
        Repository cachedRepository = cachedRepositories.get(localPath);
        if (cachedRepository != null) {
            return cachedRepository;
        }
        // Else try to retrieve the git repository from our server. It could e.g. be the case that the folder is there, but there is no .git folder in it!
        try {
            // Open the repository from the filesystem
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            final var gitPath = localPath.resolve(".git");
            builder.setGitDir(gitPath.toFile()).readEnvironment().findGitDir().setup(); // scan environment GIT_* variables
            // Create the JGit repository object
            Repository repository = new Repository(builder, localPath, remoteRepositoryUrl);
            // disable auto garbage collection because it can lead to problems (especially with deleting local repositories)
            // see https://stackoverflow.com/questions/45266021/java-jgit-files-delete-fails-to-delete-a-file-but-file-delete-succeeds
            // and https://git-scm.com/docs/git-gc for an explanation of the parameter
            repository.getConfig().setInt(ConfigConstants.CONFIG_GC_SECTION, null, ConfigConstants.CONFIG_KEY_AUTO, 0);
            // Cache the JGit repository object for later use: avoids the expensive re-opening of local repositories
            cachedRepositories.put(localPath, repository);
            return repository;
        }
        catch (IOException ex) {
            return null;
        }
    }

    /**
     * Commits with the given message into the repository.
     *
     * @param repo    Local Repository Object.
     * @param message Commit Message
     * @throws GitAPIException if the commit failed.
     */
    public void commit(Repository repo, String message) throws GitAPIException {
        Git git = new Git(repo);
        git.commit().setMessage(message).setAllowEmpty(true).setCommitter(artemisGitName, artemisGitEmail).call();
        git.close();
    }

    /**
     * Commits with the given message into the repository and pushes it to the remote.
     *
     * @param repo    Local Repository Object.
     * @param message Commit Message
     * @param user    The user who should initiate the commit. If the user is null, the artemis user will be used
     * @throws GitAPIException if the commit failed.
     */
    public void commitAndPush(Repository repo, String message, @Nullable User user) throws GitAPIException {
        var name = user != null ? user.getName() : artemisGitName;
        var email = user != null ? user.getEmail() : artemisGitEmail;
        Git git = new Git(repo);
        git.commit().setMessage(message).setAllowEmpty(true).setCommitter(name, email).call();
        log.debug("commitAndPush -> Push {}", repo.getLocalPath());
        setRemoteUrl(repo);
        git.push().setTransportConfigCallback(sshCallback).call();
        git.close();
    }

    /**
     * The remote uri of the target repo is still the uri of the source repo.
     * We need to change it to the uri of the target repo.
     * The content to be copied then gets pushed to the new repo.
     *
     * @param targetRepo    Local target repo
     * @param targetRepoUrl URI of targets repo
     * @throws GitAPIException if the repo could not be pushed
     */
    public void pushSourceToTargetRepo(Repository targetRepo, VcsRepositoryUrl targetRepoUrl) throws GitAPIException {
        Git git = new Git(targetRepo);
        try {
            // overwrite the old remote uri with the target uri
            git.remoteSetUrl().setRemoteName("origin").setRemoteUri(new URIish(getGitUriAsString(targetRepoUrl))).call();
            log.debug("pushSourceToTargetRepo -> Push {}", targetRepoUrl.getURL().toString());
            // push the source content to the new remote
            git.push().setTransportConfigCallback(sshCallback).call();
            git.close();
        }
        catch (URISyntaxException e) {
            log.error("Error while pushing to remote target: ", e);
        }
    }

    /**
     * Stage all files in the repo including new files.
     *
     * @param repo Local Repository Object.
     * @throws GitAPIException if the staging failed.
     */
    public void stageAllChanges(Repository repo) throws GitAPIException {
        Git git = new Git(repo);
        // stage deleted files: http://stackoverflow.com/a/35601677/4013020
        git.add().setUpdate(true).addFilepattern(".").call();
        // stage new files
        git.add().addFilepattern(".").call();
        git.close();
    }

    /**
     * Resets local repository to ref.
     *
     * @param repo Local Repository Object.
     * @param ref  the ref to reset to, e.g. "origin/master"
     * @throws GitAPIException if the reset failed.
     */
    public void reset(Repository repo, String ref) throws GitAPIException {
        Git git = new Git(repo);
        setRemoteUrl(repo);
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(ref).call();
        git.close();
    }

    /**
     * git fetch
     *
     * @param repo Local Repository Object.
     * @throws GitAPIException if the fetch failed.
     */
    public void fetchAll(Repository repo) throws GitAPIException {
        Git git = new Git(repo);
        log.debug("Fetch {}", repo.getLocalPath());
        setRemoteUrl(repo);
        git.fetch().setForceUpdate(true).setRemoveDeletedRefs(true).setTransportConfigCallback(sshCallback).call();
        git.close();
    }

    /**
     * Change the remote repository url to the currently used authentication mechanism (either ssh or https)
     *
     * @param repo the git repository for which the remote url should be change
     */
    private void setRemoteUrl(Repository repo) {
        if (repo == null || repo.getRemoteRepositoryUrl() == null) {
            log.warn("Cannot set remoteUrl because it is null!");
            return;
        }
        // Note: we reset the remote url, because it might have changed from https to ssh or ssh to https
        try {
            var existingRemoteUrl = repo.getConfig().getString("remote", "origin", "url");
            var newRemoteUrl = getGitUriAsString(repo.getRemoteRepositoryUrl());
            if (!Objects.equals(newRemoteUrl, existingRemoteUrl)) {
                log.info("Replace existing remote url {} with new remote url {}", existingRemoteUrl, newRemoteUrl);
                repo.getConfig().setString("remote", "origin", "url", newRemoteUrl);
                log.info("New remote url: {}", repo.getConfig().getString("remote", "origin", "url"));
            }
        }
        catch (Exception e) {
            log.warn("Cannot set the remote url", e);
        }
    }

    /**
     * Pulls from remote repository. Does not throw any exceptions when pulling, e.g. CheckoutConflictException or WrongRepositoryStateException.
     *
     * @param repo Local Repository Object.
     */
    public void pullIgnoreConflicts(Repository repo) {
        try {
            Git git = new Git(repo);
            // flush cache of files
            repo.setContent(null);
            log.debug("Pull ignore conflicts {}", repo.getLocalPath());
            setRemoteUrl(repo);
            git.pull().setTransportConfigCallback(sshCallback).call();
        }
        catch (GitAPIException ex) {
            log.error("Cannot pull the repo " + repo.getLocalPath(), ex);
            // TODO: we should send this error to the client and let the user handle it there, e.g. by choosing to reset the repository
        }
    }

    /**
     * Pulls from remote repository.
     *
     * @param repo Local Repository Object.
     * @return The PullResult which contains FetchResult and MergeResult.
     * @throws GitAPIException if the pull failed.
     */
    public PullResult pull(Repository repo) throws GitAPIException {
        Git git = new Git(repo);
        // flush cache of files
        repo.setContent(null);
        log.debug("Pull {}", repo.getLocalPath());
        setRemoteUrl(repo);
        return git.pull().setTransportConfigCallback(sshCallback).call();
    }

    /**
     * Hard reset local repository to origin/master.
     *
     * @param repo Local Repository Object.
     */
    public void resetToOriginMaster(Repository repo) {
        try {
            fetchAll(repo);
            reset(repo, "origin/master");
        }
        catch (GitAPIException | JGitInternalException ex) {
            log.error("Cannot hard reset the repo {} to origin/master due to the following exception: {}", repo.getLocalPath(), ex.getMessage());
        }
    }

    /**
     * Get last commit hash from master
     *
     * @param repoUrl to get the latest hash from.
     * @return the latestHash of the given repo.
     * @throws EntityNotFoundException if retrieving the latestHash from the git repo failed.
     */
    public ObjectId getLastCommitHash(VcsRepositoryUrl repoUrl) throws EntityNotFoundException {
        if (repoUrl == null || repoUrl.getURL() == null) {
            return null;
        }
        // Get refs of repo without cloning it locally
        Collection<Ref> refs;
        try {
            log.debug("getLastCommitHash {}", repoUrl);
            refs = Git.lsRemoteRepository().setRemote(getGitUriAsString(repoUrl)).setTransportConfigCallback(sshCallback).call();
        }
        catch (GitAPIException | URISyntaxException ex) {
            throw new EntityNotFoundException("Could not retrieve the last commit hash for repoUrl " + repoUrl + " due to the following exception: " + ex);
        }
        for (Ref ref : refs) {
            // We are looking for the latest commit hash of the master branch
            if (ref.getName().equalsIgnoreCase("refs/heads/master")) {
                return ref.getObjectId();
            }
        }
        return null;
    }

    /**
     * Stager Task #3: Filter late submissions Filter all commits after exercise due date
     *
     * @param repository                Local Repository Object.
     * @param lastValidSubmission       The last valid submission from the database or empty, if not found
     * @param filterLateSubmissionsDate the date after which all submissions should be filtered out (may be null)
     */
    // TODO: remove transactional
    @Transactional(readOnly = true)
    public void filterLateSubmissions(Repository repository, Optional<Submission> lastValidSubmission, ZonedDateTime filterLateSubmissionsDate) {
        if (filterLateSubmissionsDate == null) {
            // No date set in client and exercise has no due date
            return;
        }

        try {
            Git git = new Git(repository);

            String commitHash;

            if (lastValidSubmission.isPresent()) {
                log.debug("Last valid submission for participation {} is {}", lastValidSubmission.get().getParticipation().getId(), lastValidSubmission.get().toString());
                ProgrammingSubmission programmingSubmission = (ProgrammingSubmission) lastValidSubmission.get();
                commitHash = programmingSubmission.getCommitHash();
            }
            else {
                log.debug("Last valid submission is not present for participation");
                // Get last commit before deadline
                Date since = Date.from(Instant.EPOCH);
                Date until = Date.from(filterLateSubmissionsDate.toInstant());
                RevFilter between = CommitTimeRevFilter.between(since, until);
                Iterable<RevCommit> commits = git.log().setRevFilter(between).call();
                RevCommit latestCommitBeforeDeadline = commits.iterator().next();
                commitHash = latestCommitBeforeDeadline.getId().getName();
            }
            log.debug("Last commit hash is {}", commitHash);

            reset(repository, commitHash);

            // if repo is not closed, it causes weird IO issues when trying to delete the repo again
            // java.io.IOException: Unable to delete file: ...\.git\objects\pack\...
            repository.close();
        }
        catch (GitAPIException | JGitInternalException ex) {
            log.warn("Cannot filter the repo {} due to the following exception: {}", repository.getLocalPath(), ex.getMessage());
        }
    }

    /**
     * Stager Task #6: Combine all commits after last instructor commit
     *
     * @param repository          Local Repository Object.
     * @param programmingExercise ProgrammingExercise associated with this repo.
     */
    public void combineAllStudentCommits(Repository repository, ProgrammingExercise programmingExercise) {
        try {
            Git studentGit = new Git(repository);
            setRemoteUrl(repository);
            // Get last commit hash from template repo
            ObjectId latestHash = getLastCommitHash(programmingExercise.getVcsTemplateRepositoryUrl());

            if (latestHash == null) {
                // Template Repository is somehow empty. Should never happen
                log.debug("Cannot find a commit in the template repo for: {}", repository.getLocalPath());
                return;
            }

            // flush cache of files
            repository.setContent(null);

            // checkout own local "diff" branch
            studentGit.checkout().setCreateBranch(true).setName("diff").call();

            studentGit.reset().setMode(ResetCommand.ResetType.SOFT).setRef(latestHash.getName()).call();
            studentGit.add().addFilepattern(".").call();
            var optionalStudent = ((StudentParticipation) repository.getParticipation()).getStudents().stream().findFirst();
            var name = optionalStudent.map(User::getName).orElse(artemisGitName);
            var email = optionalStudent.map(User::getEmail).orElse(artemisGitEmail);
            studentGit.commit().setMessage("All student changes in one commit").setCommitter(name, email).call();

            // if repo is not closed, it causes weird IO issues when trying to delete the repo again
            // java.io.IOException: Unable to delete file: ...\.git\objects\pack\...
            repository.close();
        }
        catch (EntityNotFoundException | GitAPIException | JGitInternalException ex) {
            log.warn("Cannot reset the repo {} due to the following exception: {}", repository.getLocalPath(), ex.getMessage());
        }
    }

    /**
     * List all files and folders in the repository
     *
     * @param repo Local Repository Object.
     * @return Collection of File objects
     */
    public Map<File, FileType> listFilesAndFolders(Repository repo) {
        // Check if list of files is already cached
        if (repo.getContent() == null) {
            Iterator<java.io.File> itr = FileUtils.iterateFilesAndDirs(repo.getLocalPath().toFile(), HiddenFileFilter.VISIBLE, HiddenFileFilter.VISIBLE);
            Map<File, FileType> files = new HashMap<>();

            while (itr.hasNext()) {
                File nextFile = new File(itr.next(), repo);
                // Files starting with a '.' are not marked as hidden in Windows. WE must exclude these
                if (nextFile.getName().charAt(0) != '.') {
                    files.put(nextFile, nextFile.isFile() ? FileType.FILE : FileType.FOLDER);
                }
            }

            // TODO: rene: idea: ask in setup to include hidden files? only allow for tutors and instructors?
            // Current problem: .swiftlint.yml gets filtered out
            /*
             * Uncomment to show hidden files // Filter for hidden config files, e.g. '.swiftlint.yml' Iterator<java.io.File> hiddenFiles =
             * FileUtils.iterateFilesAndDirs(repo.getLocalPath().toFile(), HiddenFileFilter.HIDDEN, HiddenFileFilter.HIDDEN); while (hiddenFiles.hasNext()) { File nextFile = new
             * File(hiddenFiles.next(), repo); if (nextFile.isFile() && nextFile.getName().contains(".swiftlint")) { files.put(nextFile, FileType.FILE); } }
             */

            // Cache the list of files
            // Avoid expensive rescanning
            repo.setContent(files);
        }
        return repo.getContent();
    }

    /**
     * List all files in the repository. In an empty git repo, this method returns 0.
     *
     * @param repo Local Repository Object.
     * @return Collection of File objects
     */
    @NotNull
    public Collection<File> listFiles(Repository repo) {
        // Check if list of files is already cached
        if (repo.getFiles() == null) {
            Iterator<java.io.File> itr = FileUtils.iterateFiles(repo.getLocalPath().toFile(), HiddenFileFilter.VISIBLE, HiddenFileFilter.VISIBLE);
            Collection<File> files = new LinkedList<>();

            while (itr.hasNext()) {
                files.add(new File(itr.next(), repo));
            }

            // Cache the list of files
            // Avoid expensive rescanning
            repo.setFiles(files);
        }
        return repo.getFiles();
    }

    /**
     * Get a specific file by name. Makes sure the file is actually part of the repository.
     *
     * @param repo     Local Repository Object.
     * @param filename String of zje filename (including path)
     * @return The File object
     */
    public Optional<File> getFileByName(Repository repo, String filename) {

        // Makes sure the requested file is part of the scanned list of files.
        // Ensures that it is not possible to do bad things like filename="../../passwd"

        for (File file : listFilesAndFolders(repo).keySet()) {
            if (file.toString().equals(filename)) {
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }

    /**
     * Checks if no differences exist between the working-tree, the index, and the current HEAD.
     *
     * @param repo Local Repository Object.
     * @return True if the status is clean
     * @throws GitAPIException if the state of the repository could not be retrieved.
     */
    public Boolean isClean(Repository repo) throws GitAPIException {
        Git git = new Git(repo);
        Status status = git.status().call();
        return status.isClean();
    }

    /**
     * Combines all commits in the selected repo into the first commit, keeping its commit message. Executes a hard reset to remote before the combine to avoid conflicts.
     *
     * @param repo to combine commits for
     * @throws GitAPIException       on io errors or git exceptions.
     * @throws IllegalStateException if there is no commit in the git repository.
     */
    public void combineAllCommitsIntoInitialCommit(Repository repo) throws IllegalStateException, GitAPIException {
        Git git = new Git(repo);
        try {
            resetToOriginMaster(repo);
            List<RevCommit> commits = StreamSupport.stream(git.log().call().spliterator(), false).collect(Collectors.toList());
            RevCommit firstCommit = commits.get(commits.size() - 1);
            // If there is a first commit, combine all other commits into it.
            if (firstCommit != null) {
                git.reset().setMode(ResetCommand.ResetType.SOFT).setRef(firstCommit.getId().getName()).call();
                git.add().addFilepattern(".").call();
                git.commit().setAmend(true).setMessage(firstCommit.getFullMessage()).call();
                log.debug("combineAllCommitsIntoInitialCommit -> Push {}", repo.getLocalPath());
                git.push().setForce(true).setTransportConfigCallback(sshCallback).call();
                git.close();
            }
            else {
                // Normally there always has to be a commit, so we throw an error in case none can be found.
                throw new IllegalStateException();
            }
        }
        // This exception occurs when there was no change to the repo and a commit is done, so it is ignored.
        catch (JGitInternalException ex) {
            log.debug("Did not combine the repository {} as there were no changes to commit. Exception: {}", repo, ex.getMessage());
        }
        catch (GitAPIException ex) {
            log.error("Could not combine repository {} due to exception: {}", repo, ex);
            throw (ex);
        }
    }

    /**
     * Deletes a local repository folder.
     *
     * @param repository Local Repository Object.
     * @throws IOException if the deletion of the repository failed.
     */
    public void deleteLocalRepository(Repository repository) throws IOException {
        Path repoPath = repository.getLocalPath();
        cachedRepositories.remove(repoPath);
        // if repository is not closed, it causes weird IO issues when trying to delete the repository again
        // java.io.IOException: Unable to delete file: ...\.git\objects\pack\...
        repository.closeBeforeDelete();
        FileUtils.deleteDirectory(repoPath.toFile());
        repository.setContent(null);
        log.debug("Deleted Repository at {}", repoPath);
    }

    /**
     * Deletes a local repository folder for a repoUrl.
     *
     * @param repoUrl url of the repository.
     */
    public void deleteLocalRepository(VcsRepositoryUrl repoUrl) {
        try {
            if (repoUrl != null && repositoryAlreadyExists(repoUrl)) {
                // We need to close the possibly still open repository otherwise an IOException will be thrown on Windows
                Repository repo = getOrCheckoutRepository(repoUrl, false);
                deleteLocalRepository(repo);
            }
        }
        catch (InterruptedException | IOException | GitAPIException e) {
            log.error("Error while deleting local repository", e);
        }
    }

    /**
     * delete the folder in the file system that contains all repositories for the given programming exercise
     * @param programmingExercise contains the project key which is used as the folder name
     */
    public void deleteLocalProgrammingExerciseReposFolder(ProgrammingExercise programmingExercise) {
        var folderPath = Paths.get(repoClonePath, programmingExercise.getProjectKey());
        try {
            FileUtils.deleteDirectory(folderPath.toFile());
        }
        catch (IOException ex) {
            log.error("Exception during deleteLocalProgrammingExerciseReposFolder " + ex.getMessage(), ex);
            // cleanup the folder to avoid problems in the future.
            // 'deleteQuietly' is the same as 'deleteDirectory' but is not throwing an exception, thus we avoid a try-catch block.
            FileUtils.deleteQuietly(folderPath.toFile());
        }
    }

    /**
     * Zip the content of a git repository that contains a participation.
     *
     * @param repo            Local Repository Object.
     * @param targetPath      path where the repo is located on disk
     * @param hideStudentName option to hide the student name for the zip file
     * @return path to zip file.
     * @throws IOException if the zipping process failed.
     */
    public Path zipRepositoryWithParticipation(Repository repo, String targetPath, boolean hideStudentName) throws IOException {
        var exercise = repo.getParticipation().getProgrammingExercise();
        var courseShortName = exercise.getCourseViaExerciseGroupOrCourseMember().getShortName();
        var participation = (ProgrammingExerciseStudentParticipation) repo.getParticipation();

        // The zip filename is either the student login, team name or some default string.
        var studentTeamOrDefault = "-student-submission" + repo.getParticipation().getId();
        if (participation.getStudent().isPresent()) {
            studentTeamOrDefault = participation.getStudent().get().getLogin();
        }
        else if (participation.getTeam().isPresent()) {
            studentTeamOrDefault = participation.getTeam().get().getName();
        }

        String zipRepoName = courseShortName + "-" + exercise.getTitle();
        if (hideStudentName) {
            zipRepoName += "-student-submission.git.zip";
        }
        else {
            zipRepoName += "-" + studentTeamOrDefault + ".zip";
        }
        return zipRepository(repo.getLocalPath(), zipRepoName, targetPath);
    }

    /**
     * Zips the contents of a git repository.
     *
     * @param repoLocalPath The local path to the repository contents (e.g Repository::getLocalPath())
     * @param zipFilename   the name of the zipped file
     * @param targetPath    path where the repo is located on disk
     * @return path to the zip file
     * @throws IOException if the zipping process failed.
     */
    public Path zipRepository(Path repoLocalPath, String zipFilename, String targetPath) throws IOException {
        // Strip slashes from name
        var zipFilenameWithoutSlash = zipFilename.replaceAll("\\s", "");

        if (!zipFilenameWithoutSlash.endsWith(".zip")) {
            zipFilenameWithoutSlash += ".zip";
        }

        Path zipFilePath = Paths.get(targetPath, "zippedRepos", zipFilenameWithoutSlash);
        Files.createDirectories(Paths.get(targetPath, "zippedRepos"));
        return zipFileService.createZipFileWithFolderContent(zipFilePath, repoLocalPath);
    }

    /**
     * Generates the unique local folder name for a given remote repository URL.
     *
     * @param repoUrl URL of the remote repository.
     * @return the folderName as a string.
     */
    private String folderNameForRepositoryUrl(VcsRepositoryUrl repoUrl) {
        String path = repoUrl.getURL().getPath();
        path = path.replaceAll(".git$", "");
        path = path.replaceAll("/$", "");
        path = path.replaceAll("^/.*scm/", "");
        return path;
    }

    /**
     * Checks if repo was already checked out and is present on disk
     *
     * @param repoUrl URL of the remote repository.
     * @return True if repo exists on disk
     */
    public boolean repositoryAlreadyExists(VcsRepositoryUrl repoUrl) {
        Path localPath = getDefaultLocalPathOfRepo(repoUrl);
        return Files.exists(localPath);
    }

    /**
     * Stashes not submitted/committed changes of the repo.
     *
     * @param repo student repo of a participation in a programming exercise
     * @throws GitAPIException if the git operation does not work
     */
    public void stashChanges(Repository repo) throws GitAPIException {
        Git git = new Git(repo);
        git.stashCreate().call();
        git.close();
    }
}
