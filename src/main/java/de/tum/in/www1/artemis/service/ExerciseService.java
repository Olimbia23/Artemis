package de.tum.in.www1.artemis.service;

import static de.tum.in.www1.artemis.service.util.RoundingUtil.round;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.tum.in.www1.artemis.config.Constants;
import de.tum.in.www1.artemis.domain.*;
import de.tum.in.www1.artemis.domain.enumeration.ExerciseMode;
import de.tum.in.www1.artemis.domain.enumeration.IncludedInOverallScore;
import de.tum.in.www1.artemis.domain.enumeration.InitializationState;
import de.tum.in.www1.artemis.domain.exam.Exam;
import de.tum.in.www1.artemis.domain.exam.StudentExam;
import de.tum.in.www1.artemis.domain.lecture.ExerciseUnit;
import de.tum.in.www1.artemis.domain.participation.ProgrammingExerciseStudentParticipation;
import de.tum.in.www1.artemis.domain.participation.StudentParticipation;
import de.tum.in.www1.artemis.domain.quiz.QuizExercise;
import de.tum.in.www1.artemis.domain.quiz.QuizSubmission;
import de.tum.in.www1.artemis.domain.scores.ParticipantScore;
import de.tum.in.www1.artemis.repository.*;
import de.tum.in.www1.artemis.service.programming.ProgrammingExerciseService;
import de.tum.in.www1.artemis.service.scheduled.quiz.QuizScheduleService;
import de.tum.in.www1.artemis.web.rest.dto.CourseManagementOverviewExerciseStatisticsDTO;
import de.tum.in.www1.artemis.web.rest.errors.BadRequestAlertException;
import de.tum.in.www1.artemis.web.rest.errors.EntityNotFoundException;
import de.tum.in.www1.artemis.web.rest.util.HeaderUtil;

/**
 * Service Implementation for managing Exercise.
 */
@Service
public class ExerciseService {

    @Value("${jhipster.clientApp.name}")
    private String applicationName;

    private final Logger log = LoggerFactory.getLogger(ExerciseService.class);

    private final ParticipationService participationService;

    private final AuthorizationCheckService authCheckService;

    private final ProgrammingExerciseService programmingExerciseService;

    private final ProgrammingExerciseRepository programmingExerciseRepository;

    private final QuizExerciseService quizExerciseService;

    private final QuizScheduleService quizScheduleService;

    private final ExampleSubmissionService exampleSubmissionService;

    private final TeamRepository teamRepository;

    private final ExamRepository examRepository;

    private final StudentExamRepository studentExamRepository;

    private final AuditEventRepository auditEventRepository;

    private final ExerciseUnitRepository exerciseUnitRepository;

    private final ExerciseRepository exerciseRepository;

    private final TutorParticipationRepository tutorParticipationRepository;

    private final ParticipantScoreRepository participantScoreRepository;

    private final QuizExerciseRepository quizExerciseRepository;

    private final SubmissionRepository submissionRepository;

    private final ResultRepository resultRepository;

    private final LtiOutcomeUrlRepository ltiOutcomeUrlRepository;

    private final StudentParticipationRepository studentParticipationRepository;

    private final LectureUnitService lectureUnitService;

    public ExerciseService(ExerciseRepository exerciseRepository, ExerciseUnitRepository exerciseUnitRepository, ParticipationService participationService,
            AuthorizationCheckService authCheckService, ProgrammingExerciseService programmingExerciseService, QuizExerciseService quizExerciseService,
            QuizScheduleService quizScheduleService, TutorParticipationRepository tutorParticipationRepository, ExampleSubmissionService exampleSubmissionService,
            AuditEventRepository auditEventRepository, TeamRepository teamRepository, StudentExamRepository studentExamRepository, ExamRepository examRepository,
            ProgrammingExerciseRepository programmingExerciseRepository, QuizExerciseRepository quizExerciseRepository, LtiOutcomeUrlRepository ltiOutcomeUrlRepository,
            StudentParticipationRepository studentParticipationRepository, ResultRepository resultRepository, SubmissionRepository submissionRepository,
            ParticipantScoreRepository participantScoreRepository, LectureUnitService lectureUnitService) {
        this.exerciseRepository = exerciseRepository;
        this.resultRepository = resultRepository;
        this.examRepository = examRepository;
        this.participationService = participationService;
        this.authCheckService = authCheckService;
        this.programmingExerciseService = programmingExerciseService;
        this.tutorParticipationRepository = tutorParticipationRepository;
        this.exampleSubmissionService = exampleSubmissionService;
        this.auditEventRepository = auditEventRepository;
        this.quizExerciseService = quizExerciseService;
        this.quizScheduleService = quizScheduleService;
        this.studentExamRepository = studentExamRepository;
        this.exerciseUnitRepository = exerciseUnitRepository;
        this.submissionRepository = submissionRepository;
        this.programmingExerciseRepository = programmingExerciseRepository;
        this.teamRepository = teamRepository;
        this.participantScoreRepository = participantScoreRepository;
        this.quizExerciseRepository = quizExerciseRepository;
        this.ltiOutcomeUrlRepository = ltiOutcomeUrlRepository;
        this.studentParticipationRepository = studentParticipationRepository;
        this.lectureUnitService = lectureUnitService;
    }

    /**
     * Gets the subset of given exercises that a user is allowed to see
     *
     * @param exercises exercises to filter
     * @param user      user
     * @return subset of the exercises that a user allowed to see
     */
    public Set<Exercise> filterOutExercisesThatUserShouldNotSee(Set<Exercise> exercises, User user) {
        if (exercises == null || user == null || exercises.isEmpty()) {
            return Set.of();
        }
        // Set is needed here to remove duplicates
        Set<Course> courses = exercises.stream().map(Exercise::getCourseViaExerciseGroupOrCourseMember).collect(Collectors.toSet());
        if (courses.size() != 1) {
            throw new IllegalArgumentException("All exercises must be from the same course!");
        }
        Course course = courses.stream().findFirst().get();

        Set<Exercise> exercisesUserIsAllowedToSee = new HashSet<>();
        if (authCheckService.isAtLeastTeachingAssistantInCourse(course, user)) {
            exercisesUserIsAllowedToSee = exercises;
        }
        else if (authCheckService.isOnlyStudentInCourse(course, user)) {
            if (course.isOnlineCourse()) {
                for (Exercise exercise : exercises) {
                    if (!exercise.isVisibleToStudents()) {
                        continue;
                    }
                    // students in online courses can only see exercises where the lti outcome url exists, otherwise the result cannot be reported later on
                    Optional<LtiOutcomeUrl> ltiOutcomeUrlOptional = ltiOutcomeUrlRepository.findByUserAndExercise(user, exercise);
                    if (ltiOutcomeUrlOptional.isPresent()) {
                        exercisesUserIsAllowedToSee.add(exercise);
                    }
                }
            }
            else {
                // disclaimer: untested syntax, something along those lines should do the job however
                exercisesUserIsAllowedToSee.addAll(exercises.stream().filter(Exercise::isVisibleToStudents).collect(Collectors.toSet()));
            }
        }
        return exercisesUserIsAllowedToSee;
    }

    /**
     * Loads exercises with all the necessary information to display them correctly in the Artemis dashboard
     *
     * @param exerciseIds exercises to load
     * @param user        user to load exercise information for
     * @return exercises with all the necessary information loaded for correct display in the Artemis dashboard
     */
    public Set<Exercise> loadExercisesWithInformationForDashboard(Set<Long> exerciseIds, User user) {
        if (exerciseIds == null || user == null) {
            throw new IllegalArgumentException();
        }
        if (exerciseIds.isEmpty()) {
            return new HashSet<>();
        }
        Set<Exercise> exercises = exerciseRepository.findByExerciseIdWithCategories(exerciseIds);
        // Set is needed here to remove duplicates
        Set<Course> courses = exercises.stream().map(Exercise::getCourseViaExerciseGroupOrCourseMember).collect(Collectors.toSet());
        if (courses.size() != 1) {
            throw new IllegalArgumentException("All exercises must be from the same course!");
        }
        Course course = courses.stream().findFirst().get();
        List<StudentParticipation> participationsOfUserInExercises = getAllParticipationsOfUserInExercises(user, exercises);
        boolean isStudent = !authCheckService.isAtLeastTeachingAssistantInCourse(course, user);
        for (Exercise exercise : exercises) {
            // add participation with submission and result to each exercise
            filterForCourseDashboard(exercise, participationsOfUserInExercises, user.getLogin(), isStudent);
            // remove sensitive information from the exercise for students
            if (isStudent) {
                exercise.filterSensitiveInformation();
            }
            setAssignedTeamIdForExerciseAndUser(exercise, user);
        }
        return exercises;
    }

    /**
     * Gets all the participations of the user in the given exercises
     *
     * @param user      the user to get the participations for
     * @param exercises the exercise to get the participations for
     * @return the participations of the user in the exercises
     */
    public List<StudentParticipation> getAllParticipationsOfUserInExercises(User user, Set<Exercise> exercises) {
        Map<ExerciseMode, List<Exercise>> exercisesGroupedByExerciseMode = exercises.stream().collect(Collectors.groupingBy(Exercise::getMode));
        List<Exercise> individualExercises = Optional.ofNullable(exercisesGroupedByExerciseMode.get(ExerciseMode.INDIVIDUAL)).orElse(List.of());
        List<Exercise> teamExercises = Optional.ofNullable(exercisesGroupedByExerciseMode.get(ExerciseMode.TEAM)).orElse(List.of());

        if (individualExercises.isEmpty() && teamExercises.isEmpty()) {
            return List.of();
        }

        // Note: we need two database calls here, because of performance reasons: the entity structure for team is significantly different and a combined database call
        // would lead to a SQL statement that cannot be optimized

        // 1st: fetch participations, submissions and results for individual exercises
        List<StudentParticipation> individualParticipations = studentParticipationRepository
                .findByStudentIdAndIndividualExercisesWithEagerLegalSubmissionsResultIgnoreTestRuns(user.getId(), individualExercises);

        // 2nd: fetch participations, submissions and results for team exercises
        List<StudentParticipation> teamParticipations = studentParticipationRepository.findByStudentIdAndTeamExercisesWithEagerLegalSubmissionsResult(user.getId(), teamExercises);

        // 3rd: merge both into one list for further processing
        return Stream.concat(individualParticipations.stream(), teamParticipations.stream()).collect(Collectors.toList());
    }

    /**
     * Finds all Exercises for a given Course
     *
     * @param course corresponding course
     * @param user   the user entity
     * @return a List of all Exercises for the given course
     */
    public Set<Exercise> findAllForCourse(Course course, User user) {
        Set<Exercise> exercises = null;
        if (authCheckService.isAtLeastTeachingAssistantInCourse(course, user)) {
            // tutors/instructors/admins can see all exercises of the course
            exercises = exerciseRepository.findByCourseIdWithCategories(course.getId());
        }
        else if (authCheckService.isOnlyStudentInCourse(course, user)) {

            if (course.isOnlineCourse()) {
                // students in online courses can only see exercises where the lti outcome url exists, otherwise the result cannot be reported later on
                exercises = exerciseRepository.findByCourseIdWhereLtiOutcomeUrlExists(course.getId(), user.getLogin());
            }
            else {
                exercises = exerciseRepository.findByCourseIdWithCategories(course.getId());
            }

            // students for this course might not have the right to see it so we have to
            // filter out exercises that are not released (or explicitly made visible to students) yet
            exercises = exercises.stream().filter(Exercise::isVisibleToStudents).collect(Collectors.toSet());
        }

        if (exercises != null) {
            for (Exercise exercise : exercises) {
                setAssignedTeamIdForExerciseAndUser(exercise, user);

                // filter out questions and all statistical information about the quizPointStatistic from quizExercises (so users can't see which answer options are correct)
                if (exercise instanceof QuizExercise) {
                    QuizExercise quizExercise = (QuizExercise) exercise;
                    quizExercise.filterSensitiveInformation();
                }
            }
        }

        return exercises;
    }

    /**
     * Checks if the exercise has any test runs and sets the transient property if it does
     * @param exercise - the exercise for which we check if test runs exist
     */
    public void checkTestRunsExist(Exercise exercise) {
        Long containsTestRunParticipations = studentParticipationRepository.countParticipationsOnlyTestRunsByExerciseId(exercise.getId());
        if (containsTestRunParticipations != null && containsTestRunParticipations > 0) {
            exercise.setTestRunParticipationsExist(Boolean.TRUE);
        }
    }

    /**
     * Resets an Exercise by deleting all its participations
     * @param exercise which should be reset
     */
    public void reset(Exercise exercise) {
        log.debug("Request reset Exercise : {}", exercise.getId());

        // delete all participations for this exercise
        participationService.deleteAllByExerciseId(exercise.getId(), true, true);

        if (exercise instanceof QuizExercise) {
            quizExerciseService.resetExercise(exercise.getId());
        }
    }

    /**
     * Delete the exercise by id and all its participations.
     *
     * @param exerciseId                   the exercise to be deleted
     * @param deleteStudentReposBuildPlans whether the student repos and build plans should be deleted (can be true for programming exercises and should be false for all other exercise types)
     * @param deleteBaseReposBuildPlans    whether the template and solution repos and build plans should be deleted (can be true for programming exercises and should be false for all other exercise types)
     */
    @Transactional // ok
    public void delete(long exerciseId, boolean deleteStudentReposBuildPlans, boolean deleteBaseReposBuildPlans) {
        // Delete has a transactional mechanism. Therefore, all lazy objects that are deleted below, should be fetched when needed.
        final var exercise = exerciseRepository.findByIdElseThrow(exerciseId);
        participantScoreRepository.deleteAllByExerciseIdTransactional(exerciseId);
        // delete all exercise units linking to the exercise
        List<ExerciseUnit> exerciseUnits = this.exerciseUnitRepository.findByIdWithLearningGoalsBidirectional(exerciseId);
        for (ExerciseUnit exerciseUnit : exerciseUnits) {
            this.lectureUnitService.removeLectureUnit(exerciseUnit);
        }

        // delete all participations belonging to this quiz
        participationService.deleteAllByExerciseId(exercise.getId(), deleteStudentReposBuildPlans, deleteStudentReposBuildPlans);
        // clean up the many to many relationship to avoid problems when deleting the entities but not the relationship table
        // to avoid a ConcurrentModificationException, we need to use a copy of the set
        var exampleSubmissions = new HashSet<>(exercise.getExampleSubmissions());
        for (ExampleSubmission exampleSubmission : exampleSubmissions) {
            exampleSubmissionService.deleteById(exampleSubmission.getId());
        }
        // make sure tutor participations are deleted before the exercise is deleted
        tutorParticipationRepository.deleteAllByAssessedExerciseId(exercise.getId());

        if (exercise.isExamExercise()) {
            Exam exam = examRepository.findOneWithEagerExercisesGroupsAndStudentExams(exercise.getExerciseGroup().getExam().getId());
            for (StudentExam studentExam : exam.getStudentExams()) {
                if (studentExam.getExercises().contains(exercise)) {
                    // remove exercise reference from student exam
                    List<Exercise> exerciseList = studentExam.getExercises();
                    exerciseList.remove(exercise);
                    studentExam.setExercises(exerciseList);
                    studentExamRepository.save(studentExam);
                }
            }
        }

        // Programming exercises have some special stuff that needs to be cleaned up (solution/template participation, build plans, etc.).
        if (exercise instanceof ProgrammingExercise) {
            // TODO: delete all schedules related to this programming exercise
            programmingExerciseService.delete(exercise.getId(), deleteBaseReposBuildPlans);
        }
        else {
            exerciseRepository.delete(exercise);
        }
    }

    /**
     * Updates the points of related exercises if the points of exercises have changed
     *
     * @param originalExercise the original exercise
     * @param updatedExercise  the updatedExercise
     */
    public void updatePointsInRelatedParticipantScores(Exercise originalExercise, Exercise updatedExercise) {
        if (originalExercise.getMaxPoints().equals(updatedExercise.getMaxPoints()) && originalExercise.getBonusPoints().equals(updatedExercise.getBonusPoints())) {
            return; // nothing to do since points are still correct
        }

        List<ParticipantScore> participantScoreList = participantScoreRepository.findAllByExercise(updatedExercise);
        for (ParticipantScore participantScore : participantScoreList) {
            Double lastPoints = null;
            Double lastRatedPoints = null;
            if (participantScore.getLastScore() != null) {
                lastPoints = round(participantScore.getLastScore() * 0.01 * updatedExercise.getMaxPoints());
            }
            if (participantScore.getLastRatedScore() != null) {
                lastRatedPoints = round(participantScore.getLastRatedScore() * 0.01 * updatedExercise.getMaxPoints());
            }
            participantScore.setLastPoints(lastPoints);
            participantScore.setLastRatedPoints(lastRatedPoints);
        }
        participantScoreRepository.saveAll(participantScoreList);
    }

    /**
     * Delete student build plans (except BASE/SOLUTION) and optionally git repositories of all exercise student participations.
     *
     * @param exerciseId         programming exercise for which build plans in respective student participations are deleted
     * @param deleteRepositories if true, the repositories gets deleted
     */
    public void cleanup(Long exerciseId, boolean deleteRepositories) {
        Exercise exercise = exerciseRepository.findByIdWithStudentParticipationsElseThrow(exerciseId);
        log.info("Request to cleanup all participations for Exercise : {}", exercise.getTitle());

        if (exercise instanceof ProgrammingExercise) {
            for (StudentParticipation participation : exercise.getStudentParticipations()) {
                participationService.cleanupBuildPlan((ProgrammingExerciseStudentParticipation) participation);
            }

            if (!deleteRepositories) {
                return;    // in this case, we are done
            }

            for (StudentParticipation participation : exercise.getStudentParticipations()) {
                participationService.cleanupRepository((ProgrammingExerciseStudentParticipation) participation);
            }

        }
        else {
            log.warn("Exercise with exerciseId {} is not an instance of ProgrammingExercise. Ignoring the request to cleanup repositories and build plan", exerciseId);
        }
    }

    public void logDeletion(Exercise exercise, Course course, User user) {
        var auditEvent = new AuditEvent(user.getLogin(), Constants.DELETE_EXERCISE, "exercise=" + exercise.getTitle(), "course=" + course.getTitle());
        auditEventRepository.add(auditEvent);
        log.info("User {} has requested to delete {} {} with id {}", user.getLogin(), exercise.getClass().getSimpleName(), exercise.getTitle(), exercise.getId());
    }

    /**
     * Check to ensure that an updatedExercise is not converted from a course exercise to an exam exercise and vice versa.
     *
     * @param updatedExercise the updated Exercise
     * @param oldExercise     the old Exercise
     * @param entityName      name of the entity
     * @throws BadRequestAlertException if updated exercise was converted
     */
    public void checkForConversionBetweenExamAndCourseExercise(Exercise updatedExercise, Exercise oldExercise, String entityName) throws BadRequestAlertException {
        if (updatedExercise.isExamExercise() != oldExercise.isExamExercise() || updatedExercise.isCourseExercise() != oldExercise.isCourseExercise()) {
            throw new BadRequestAlertException("Course exercise cannot be converted to exam exercise and vice versa", entityName, "conversionBetweenExamAndCourseExercise");
        }
    }

    /**
     * Find the participation in participations that belongs to the given exercise that includes the exercise data, plus the found participation with its most recent relevant
     * result. Filter everything else that is not relevant
     *
     * @param exercise       the exercise that should be filtered (this deletes many field values of the passed exercise object)
     * @param participations the set of participations, wherein to search for the relevant participation
     * @param username       used to get quiz submission for the user
     * @param isStudent      defines if the current user is a student
     */
    public void filterForCourseDashboard(Exercise exercise, List<StudentParticipation> participations, String username, boolean isStudent) {
        // remove the unnecessary inner course attribute
        exercise.setCourse(null);

        // remove the problem statement, which is loaded in the exercise details call
        exercise.setProblemStatement(null);

        if (exercise instanceof ProgrammingExercise) {
            var programmingExercise = (ProgrammingExercise) exercise;
            programmingExercise.setTestRepositoryUrl(null);
        }

        // get user's participation for the exercise
        StudentParticipation participation = participations != null ? exercise.findRelevantParticipation(participations) : null;

        // for quiz exercises also check SubmissionHashMap for submission by this user (active participation)
        // if participation was not found in database
        if (participation == null && exercise instanceof QuizExercise) {
            QuizSubmission submission = quizScheduleService.getQuizSubmission(exercise.getId(), username);
            if (submission.getSubmissionDate() != null) {
                participation = new StudentParticipation().exercise(exercise);
                participation.initializationState(InitializationState.INITIALIZED);
            }
        }

        // add relevant submission (relevancy depends on InitializationState) with its result to participation
        if (participation != null) {
            // find the latest submission with a rated result, otherwise the latest submission with
            // an unrated result or alternatively the latest submission without a result
            Set<Submission> submissions = participation.getSubmissions();

            // only transmit the relevant result
            // TODO: we should sync the following two and make sure that we return the correct submission and/or result in all scenarios
            Submission submission = (submissions == null || submissions.isEmpty()) ? null : exercise.findAppropriateSubmissionByResults(submissions);
            Submission latestSubmissionWithRatedResult = participation.getExercise().findLatestSubmissionWithRatedResultWithCompletionDate(participation, false);

            Set<Result> results = Set.of();

            if (latestSubmissionWithRatedResult != null && latestSubmissionWithRatedResult.getLatestResult() != null) {
                results = Set.of(latestSubmissionWithRatedResult.getLatestResult());
                // remove inner participation from result
                latestSubmissionWithRatedResult.getLatestResult().setParticipation(null);
                // filter sensitive information about the assessor if the current user is a student
                if (isStudent) {
                    latestSubmissionWithRatedResult.getLatestResult().filterSensitiveInformation();
                }
            }

            // filter sensitive information in submission's result
            if (isStudent && submission != null && submission.getLatestResult() != null) {
                submission.getLatestResult().filterSensitiveInformation();
            }

            // add submission to participation or set it to null
            participation.setSubmissions(submission != null ? Set.of(submission) : null);

            participation.setResults(results);

            // remove inner exercise from participation
            participation.setExercise(null);

            // add participation into an array
            exercise.setStudentParticipations(Set.of(participation));
        }
    }

    /**
     * Get one exercise with all exercise hints and all student questions + answers and with all categories
     *
     * @param exerciseId the id of the exercise to find
     * @param user       the current user
     * @return the exercise
     */
    public Exercise findOneWithDetailsForStudents(Long exerciseId, User user) {
        var exercise = exerciseRepository.findByIdWithDetailsForStudent(exerciseId).orElseThrow(() -> new EntityNotFoundException("Exercise", exerciseId));
        setAssignedTeamIdForExerciseAndUser(exercise, user);
        return exercise;
    }

    /**
     * Sets the transient attribute "studentAssignedTeamId" that contains the id of the team to which the user is assigned
     *
     * @param exercise the exercise for which to set the attribute
     * @param user     the user for which to check to which team (or no team) he belongs to
     */
    private void setAssignedTeamIdForExerciseAndUser(Exercise exercise, User user) {
        // if the exercise is not team-based, there is nothing to do here
        if (exercise.isTeamMode()) {
            Optional<Team> team = teamRepository.findOneByExerciseIdAndUserId(exercise.getId(), user.getId());
            exercise.setStudentAssignedTeamId(team.map(Team::getId).orElse(null));
            exercise.setStudentAssignedTeamIdComputed(true);
        }
    }

    /**
     * Gets the exercise statistics by setting values for each field of the <code>CourseManagementOverviewExerciseStatisticsDTO</code>
     * Exercises with an assessment due date (or due date if there is no assessment due date) in the past are limited to the five most recent
     *
     * @param courseId the id of the course
     * @param amountOfStudentsInCourse the amount of students in the course
     * @return A list of filled <code>CourseManagementOverviewExerciseStatisticsDTO</code>
     */
    public List<CourseManagementOverviewExerciseStatisticsDTO> getStatisticsForCourseManagementOverview(Long courseId, Integer amountOfStudentsInCourse) {
        // We only display the latest five past exercises in the client, only calculate statistics for those
        var pastExercises = exerciseRepository.getPastExercisesForCourseManagementOverview(courseId, ZonedDateTime.now());
        pastExercises.sort((exerciseA, exerciseB) -> {
            var dueDateA = exerciseA.getAssessmentDueDate() != null ? exerciseA.getAssessmentDueDate() : exerciseA.getDueDate();
            var dueDateB = exerciseB.getAssessmentDueDate() != null ? exerciseB.getAssessmentDueDate() : exerciseB.getDueDate();
            if (dueDateA.equals(dueDateB)) {
                return 0;
            }

            return dueDateA.isBefore(dueDateB) ? 1 : -1;
        });
        var fivePastExercises = pastExercises.stream().limit(5).collect(Collectors.toList());

        // Calculate the average score for all five exercises at once
        var averageScore = participantScoreRepository.findAverageScoreForExercises(fivePastExercises);
        Map<Long, Double> averageScoreById = new HashMap<>();
        for (var element : averageScore) {
            averageScoreById.put((Long) element.get("exerciseId"), (Double) element.get("averageScore"));
        }

        // Fill statistics for all exercises potentially displayed on the client
        var exercisesForManagementOverview = exerciseRepository.getActiveExercisesForCourseManagementOverview(courseId, ZonedDateTime.now());
        exercisesForManagementOverview.addAll(fivePastExercises);
        return generateCourseManagementDTOs(exercisesForManagementOverview, amountOfStudentsInCourse, averageScoreById);
    }

    /**
     * Generates a <code>CourseManagementOverviewExerciseStatisticsDTO</code> for each given exercise
     *
     * @param exercisesForManagementOverview a set of exercises to generate the statistics for
     * @param amountOfStudentsInCourse the amount of students in the course
     * @param averageScoreById the average score for each exercise indexed by exerciseId
     * @return A list of filled <code>CourseManagementOverviewExerciseStatisticsDTO</code>
     */
    private List<CourseManagementOverviewExerciseStatisticsDTO> generateCourseManagementDTOs(Set<Exercise> exercisesForManagementOverview, Integer amountOfStudentsInCourse,
            Map<Long, Double> averageScoreById) {
        List<CourseManagementOverviewExerciseStatisticsDTO> statisticsDTOS = new ArrayList<>();
        for (var exercise : exercisesForManagementOverview) {
            var exerciseId = exercise.getId();
            var exerciseStatisticsDTO = new CourseManagementOverviewExerciseStatisticsDTO();
            exerciseStatisticsDTO.setExerciseId(exerciseId);
            exerciseStatisticsDTO.setExerciseMaxPoints(exercise.getMaxPoints());

            setAverageScoreForStatisticsDTO(exerciseStatisticsDTO, averageScoreById, exercise);
            setStudentsAndParticipationsAmountForStatisticsDTO(exerciseStatisticsDTO, amountOfStudentsInCourse, exercise);
            setAssessmentsAndSubmissionsForStatisticsDTO(exerciseStatisticsDTO, exercise);

            statisticsDTOS.add(exerciseStatisticsDTO);
        }
        return statisticsDTOS;
    }

    /**
     * Sets the average for the given <code>CourseManagementOverviewExerciseStatisticsDTO</code>
     * using the value provided in averageScoreById
     *
     * Quiz Exercises are a special case: They don't have a due date set in the database,
     * therefore it is hard to tell if they are over, so always calculate a score for them
     *
     * @param exerciseStatisticsDTO the <code>CourseManagementOverviewExerciseStatisticsDTO</code> to set the amounts for
     * @param averageScoreById the average score for each exercise indexed by exerciseId
     * @param exercise the exercise corresponding to the <code>CourseManagementOverviewExerciseStatisticsDTO</code>
     */
    private void setAverageScoreForStatisticsDTO(CourseManagementOverviewExerciseStatisticsDTO exerciseStatisticsDTO, Map<Long, Double> averageScoreById, Exercise exercise) {
        if (exercise instanceof QuizExercise) {
            var averageScore = participantScoreRepository.findAverageScoreForExercises(exercise.getId());
            exerciseStatisticsDTO.setAverageScoreInPercent(averageScore != null ? averageScore : 0.0);
        }
        else {
            var averageScore = averageScoreById.get(exercise.getId());
            exerciseStatisticsDTO.setAverageScoreInPercent(averageScore != null ? averageScore : 0.0);
        }
    }

    /**
     * Sets the amount of students, participations and teams for the given <code>CourseManagementOverviewExerciseStatisticsDTO</code>
     * Only the amount of students in the course is set if the exercise has ended, the rest is set to zero
     *
     * @param exerciseStatisticsDTO the <code>CourseManagementOverviewExerciseStatisticsDTO</code> to set the amounts for
     * @param amountOfStudentsInCourse the amount of students in the course
     * @param exercise the exercise corresponding to the <code>CourseManagementOverviewExerciseStatisticsDTO</code>
     */
    private void setStudentsAndParticipationsAmountForStatisticsDTO(CourseManagementOverviewExerciseStatisticsDTO exerciseStatisticsDTO, Integer amountOfStudentsInCourse,
            Exercise exercise) {
        exerciseStatisticsDTO.setNoOfStudentsInCourse(amountOfStudentsInCourse);

        if (amountOfStudentsInCourse != null && amountOfStudentsInCourse != 0 && !exercise.isEnded()) {
            if (exercise.getMode() == ExerciseMode.TEAM) {
                Long teamParticipations = exerciseRepository.getTeamParticipationCountById(exercise.getId());
                var participations = teamParticipations == null ? 0 : Math.toIntExact(teamParticipations);
                exerciseStatisticsDTO.setNoOfParticipatingStudentsOrTeams(participations);

                Integer teams = teamRepository.getNumberOfTeamsForExercise(exercise.getId());
                exerciseStatisticsDTO.setNoOfTeamsInCourse(teams);
                exerciseStatisticsDTO.setParticipationRateInPercent(teams == null || teams == 0 ? 0.0 : Math.round(participations * 1000.0 / teams) / 10.0);
            }
            else {
                Long studentParticipations = exerciseRepository.getStudentParticipationCountById(exercise.getId());
                var participations = studentParticipations == null ? 0 : Math.toIntExact(studentParticipations);
                exerciseStatisticsDTO.setNoOfParticipatingStudentsOrTeams(participations);

                exerciseStatisticsDTO.setParticipationRateInPercent(Math.round(participations * 1000.0 / amountOfStudentsInCourse) / 10.0);
            }
        }
        else {
            exerciseStatisticsDTO.setNoOfParticipatingStudentsOrTeams(0);
            exerciseStatisticsDTO.setParticipationRateInPercent(0D);
        }
    }

    /**
     * Sets the amount of rated assessments and submissions done for the given <code>CourseManagementOverviewExerciseStatisticsDTO</code>
     * The amounts are set to zero if the assessment due date has passed
     *
     * @param exerciseStatisticsDTO the <code>CourseManagementOverviewExerciseStatisticsDTO</code> to set the amounts for
     * @param exercise the exercise corresponding to the <code>CourseManagementOverviewExerciseStatisticsDTO</code>
     */
    private void setAssessmentsAndSubmissionsForStatisticsDTO(CourseManagementOverviewExerciseStatisticsDTO exerciseStatisticsDTO, Exercise exercise) {
        if (exercise.getAssessmentDueDate() != null && exercise.getAssessmentDueDate().isAfter(ZonedDateTime.now())) {
            long numberOfRatedAssessments = resultRepository.countNumberOfRatedResultsForExercise(exercise.getId());
            long noOfSubmissionsInTime = submissionRepository.countUniqueSubmissionsByExerciseId(exercise.getId());
            exerciseStatisticsDTO.setNoOfRatedAssessments(numberOfRatedAssessments);
            exerciseStatisticsDTO.setNoOfSubmissionsInTime(noOfSubmissionsInTime);
            exerciseStatisticsDTO.setNoOfAssessmentsDoneInPercent(noOfSubmissionsInTime == 0 ? 0 : Math.round(numberOfRatedAssessments * 1000.0 / noOfSubmissionsInTime) / 10.0);
        }
        else {
            exerciseStatisticsDTO.setNoOfRatedAssessments(0L);
            exerciseStatisticsDTO.setNoOfSubmissionsInTime(0L);
            exerciseStatisticsDTO.setNoOfAssessmentsDoneInPercent(0D);
        }
    }

    /**
     * Validates score settings
     * 1. The maxScore needs to be greater than 0
     * 2. If the IncludedInOverallScore enum is either INCLUDED_AS_BONUS or NOT_INCLUDED, no bonus points are allowed
     *
     * @param exercise exercise to validate
     * @param <T>      specific type of exercise
     * @return Optional validation error response
     */
    public <T extends Exercise> Optional<ResponseEntity<T>> validateScoreSettings(T exercise) {
        // Check if max score is set
        if (exercise.getMaxPoints() == null || exercise.getMaxPoints() == 0) {
            return Optional
                    .of(ResponseEntity.badRequest().headers(HeaderUtil.createAlert(applicationName, "The max score needs to be greater than 0", "maxScoreInvalid")).body(null));
        }

        // Check IncludedInOverallScore
        if ((exercise.getIncludedInOverallScore() == IncludedInOverallScore.INCLUDED_AS_BONUS || exercise.getIncludedInOverallScore() == IncludedInOverallScore.NOT_INCLUDED)
                && exercise.getBonusPoints() > 0) {
            return Optional.of(ResponseEntity.badRequest().headers(HeaderUtil.createAlert(applicationName, "Bonus points are not allowed", "bonusPointsInvalid")).body(null));
        }

        if (exercise.getBonusPoints() == null) {
            // make sure the default value is set properly
            exercise.setBonusPoints(0.0);
        }
        return Optional.empty();
    }
}
