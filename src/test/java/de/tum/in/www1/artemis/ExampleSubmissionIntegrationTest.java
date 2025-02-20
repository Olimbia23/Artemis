package de.tum.in.www1.artemis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;

import de.tum.in.www1.artemis.domain.*;
import de.tum.in.www1.artemis.domain.enumeration.AssessmentType;
import de.tum.in.www1.artemis.domain.enumeration.FeedbackType;
import de.tum.in.www1.artemis.domain.modeling.ModelingExercise;
import de.tum.in.www1.artemis.domain.modeling.ModelingSubmission;
import de.tum.in.www1.artemis.domain.participation.TutorParticipation;
import de.tum.in.www1.artemis.repository.ExampleSubmissionRepository;
import de.tum.in.www1.artemis.repository.ResultRepository;
import de.tum.in.www1.artemis.util.FileUtils;
import de.tum.in.www1.artemis.web.rest.dto.TextAssessmentDTO;

public class ExampleSubmissionIntegrationTest extends AbstractSpringIntegrationBambooBitbucketJiraTest {

    @Autowired
    private ResultRepository resultRepo;

    @Autowired
    private ExampleSubmissionRepository exampleSubmissionRepo;

    private ModelingExercise modelingExercise;

    private TextExercise textExercise;

    private ExampleSubmission exampleSubmission;

    private String emptyModel;

    private String validModel;

    @BeforeEach
    public void initTestCase() throws Exception {
        database.addUsers(1, 1, 1);
        Course course = database.addCourseWithModelingAndTextExercise();
        modelingExercise = (ModelingExercise) course.getExercises().stream().filter(exercise -> exercise instanceof ModelingExercise).findFirst().get();
        textExercise = (TextExercise) course.getExercises().stream().filter(exercise -> exercise instanceof TextExercise).findFirst().get();
        emptyModel = FileUtils.loadFileFromResources("test-data/model-submission/empty-class-diagram.json");
        validModel = FileUtils.loadFileFromResources("test-data/model-submission/model.54727.json");
    }

    @AfterEach
    public void tearDown() {
        database.resetDatabase();
    }

    @Test
    @WithMockUser(value = "instructor1", roles = "INSTRUCTOR")
    public void createAndUpdateExampleModelingSubmission() throws Exception {
        exampleSubmission = database.generateExampleSubmission(emptyModel, modelingExercise, false);
        ExampleSubmission returnedExampleSubmission = request.postWithResponseBody("/api/exercises/" + modelingExercise.getId() + "/example-submissions", exampleSubmission,
                ExampleSubmission.class, HttpStatus.OK);

        database.checkModelingSubmissionCorrectlyStored(returnedExampleSubmission.getSubmission().getId(), emptyModel);
        Optional<ExampleSubmission> storedExampleSubmission = exampleSubmissionRepo.findBySubmissionId(returnedExampleSubmission.getSubmission().getId());
        assertThat(storedExampleSubmission).as("example submission correctly stored").isPresent();
        assertThat(storedExampleSubmission.get().getSubmission().isExampleSubmission()).as("submission flagged as example submission").isTrue();

        exampleSubmission = database.generateExampleSubmission(validModel, modelingExercise, false);
        returnedExampleSubmission = request.postWithResponseBody("/api/exercises/" + modelingExercise.getId() + "/example-submissions", exampleSubmission, ExampleSubmission.class,
                HttpStatus.OK);

        database.checkModelingSubmissionCorrectlyStored(returnedExampleSubmission.getSubmission().getId(), validModel);
        storedExampleSubmission = exampleSubmissionRepo.findBySubmissionId(returnedExampleSubmission.getSubmission().getId());
        assertThat(storedExampleSubmission).as("example submission correctly stored").isPresent();
        assertThat(storedExampleSubmission.get().getSubmission().isExampleSubmission()).as("submission flagged as example submission").isTrue();
    }

    @Test
    @WithMockUser(value = "instructor1", roles = "INSTRUCTOR")
    public void updateExampleModelingSubmission() throws Exception {
        exampleSubmission = database.generateExampleSubmission(emptyModel, modelingExercise, false);
        ExampleSubmission returnedExampleSubmission = request.postWithResponseBody("/api/exercises/" + modelingExercise.getId() + "/example-submissions", exampleSubmission,
                ExampleSubmission.class, HttpStatus.OK);
        ExampleSubmission updateExistingExampleSubmission = request.putWithResponseBody("/api/exercises/" + modelingExercise.getId() + "/example-submissions",
                returnedExampleSubmission, ExampleSubmission.class, HttpStatus.OK);

        database.checkModelingSubmissionCorrectlyStored(updateExistingExampleSubmission.getSubmission().getId(), emptyModel);
        Optional<ExampleSubmission> storedExampleSubmission = exampleSubmissionRepo.findBySubmissionId(updateExistingExampleSubmission.getSubmission().getId());
        assertThat(storedExampleSubmission).as("example submission correctly stored").isPresent();
        assertThat(storedExampleSubmission.get().getSubmission().isExampleSubmission()).as("submission flagged as example submission").isTrue();

        ExampleSubmission updatedExampleSubmission = database.generateExampleSubmission(validModel, modelingExercise, false);
        ExampleSubmission returnedUpdatedExampleSubmission = request.putWithResponseBody("/api/exercises/" + modelingExercise.getId() + "/example-submissions",
                updatedExampleSubmission, ExampleSubmission.class, HttpStatus.OK);

        database.checkModelingSubmissionCorrectlyStored(returnedUpdatedExampleSubmission.getSubmission().getId(), validModel);
        storedExampleSubmission = exampleSubmissionRepo.findBySubmissionId(returnedUpdatedExampleSubmission.getSubmission().getId());
        assertThat(storedExampleSubmission).as("example submission correctly stored").isPresent();
        assertThat(storedExampleSubmission.get().getSubmission().isExampleSubmission()).as("submission flagged as example submission").isTrue();
    }

    @Test
    @WithMockUser(value = "instructor1", roles = "INSTRUCTOR")
    public void createAndDeleteExampleModelingSubmission() throws Exception {
        exampleSubmission = database.generateExampleSubmission(validModel, modelingExercise, false);
        ExampleSubmission returnedExampleSubmission = request.postWithResponseBody("/api/exercises/" + modelingExercise.getId() + "/example-submissions", exampleSubmission,
                ExampleSubmission.class, HttpStatus.OK);
        Long submissionId = returnedExampleSubmission.getSubmission().getId();

        database.checkModelingSubmissionCorrectlyStored(submissionId, validModel);
        Optional<ExampleSubmission> storedExampleSubmission = exampleSubmissionRepo.findBySubmissionId(submissionId);
        assertThat(storedExampleSubmission).as("example submission correctly stored").isPresent();
        assertThat(storedExampleSubmission.get().getSubmission().isExampleSubmission()).as("submission flagged as example submission").isTrue();

        request.delete("/api/example-submissions/" + storedExampleSubmission.get().getId(), HttpStatus.OK);
        assertThat(exampleSubmissionRepo.findAllByExerciseId(submissionId)).hasSize(0);
    }

    @Test
    @WithMockUser(value = "instructor1", roles = "INSTRUCTOR")
    public void createAndDeleteExampleModelingSubmissionWithResult() throws Exception {
        exampleSubmission = database.generateExampleSubmission(validModel, modelingExercise, false);
        exampleSubmission.setUsedForTutorial(true);
        exampleSubmission.addTutorParticipations(new TutorParticipation());
        ExampleSubmission returnedExampleSubmission = request.postWithResponseBody("/api/exercises/" + modelingExercise.getId() + "/example-submissions", exampleSubmission,
                ExampleSubmission.class, HttpStatus.OK);
        Long submissionId = returnedExampleSubmission.getSubmission().getId();

        database.checkModelingSubmissionCorrectlyStored(submissionId, validModel);
        Optional<ExampleSubmission> storedExampleSubmission = exampleSubmissionRepo.findBySubmissionId(submissionId);
        assertThat(storedExampleSubmission).as("example submission correctly stored").isPresent();
        assertThat(storedExampleSubmission.get().getSubmission().isExampleSubmission()).as("submission flagged as example submission").isTrue();

        request.delete("/api/example-submissions/" + storedExampleSubmission.get().getId(), HttpStatus.OK);
        assertThat(exampleSubmissionRepo.findAllByExerciseId(submissionId)).hasSize(0);
    }

    @Test
    @WithMockUser(value = "tutor1", roles = "TA")
    public void createExampleModelingSubmission_asTutor_forbidden() throws Exception {
        exampleSubmission = database.generateExampleSubmission(emptyModel, modelingExercise, true);
        request.post("/api/exercises/" + modelingExercise.getId() + "/example-submissions", exampleSubmission, HttpStatus.FORBIDDEN);
    }

    @Test
    @WithMockUser(value = "student1")
    public void createExampleModelingSubmission_asStudent_forbidden() throws Exception {
        exampleSubmission = database.generateExampleSubmission(emptyModel, modelingExercise, true);
        request.post("/api/exercises/" + modelingExercise.getId() + "/example-submissions", exampleSubmission, HttpStatus.FORBIDDEN);
    }

    @Test
    @WithMockUser(value = "tutor1", roles = "TA")
    public void getExampleModelingSubmission() throws Exception {
        ExampleSubmission storedExampleSubmission = database.addExampleSubmission(database.generateExampleSubmission(validModel, modelingExercise, true));

        exampleSubmission = request.get("/api/example-submissions/" + storedExampleSubmission.getId(), HttpStatus.OK, ExampleSubmission.class);

        database.checkModelsAreEqual(((ModelingSubmission) exampleSubmission.getSubmission()).getModel(), validModel);
    }

    @Test
    @WithMockUser(value = "student1")
    public void getExampleModelingSubmission_asStudent_forbidden() throws Exception {
        ExampleSubmission storedExampleSubmission = database.addExampleSubmission(database.generateExampleSubmission(validModel, modelingExercise, true));

        request.get("/api/example-submissions/" + storedExampleSubmission.getId(), HttpStatus.FORBIDDEN, ExampleSubmission.class);
    }

    @Test
    @WithMockUser(value = "tutor1", roles = "TA")
    public void createExampleModelingAssessment() throws Exception {
        ExampleSubmission storedExampleSubmission = database.addExampleSubmission(database.generateExampleSubmission(validModel, modelingExercise, true));
        List<Feedback> feedbacks = database.loadAssessmentFomResources("test-data/model-assessment/assessment.54727.json");

        request.putWithResponseBody("/api/modeling-submissions/" + storedExampleSubmission.getId() + "/example-assessment", feedbacks, Result.class, HttpStatus.OK);

        Result storedResult = resultRepo.findDistinctWithFeedbackBySubmissionId(storedExampleSubmission.getSubmission().getId()).get();
        database.checkFeedbackCorrectlyStored(feedbacks, storedResult.getFeedbacks(), FeedbackType.MANUAL);
        assertThat(storedResult.isExampleResult()).as("stored result is flagged as example result").isTrue();
    }

    @Test
    @WithMockUser(value = "instructor1", roles = "INSTRUCTOR")
    public void createExampleTextAssessment() throws Exception {
        ExampleSubmission storedExampleSubmission = database.addExampleSubmission(database.generateExampleSubmission("Text. Submission.", textExercise, true));
        database.addResultToSubmission(storedExampleSubmission.getSubmission(), AssessmentType.MANUAL);
        final Result exampleResult = request.get(
                "/api/text-assessments/exercise/" + textExercise.getId() + "/submission/" + storedExampleSubmission.getSubmission().getId() + "/example-result", HttpStatus.OK,
                Result.class);
        final Set<TextBlock> blocks = ((TextSubmission) exampleResult.getSubmission()).getBlocks();
        assertThat(blocks).hasSize(2);
        List<Feedback> feedbacks = new ArrayList<>();
        final Iterator<TextBlock> textBlockIterator = blocks.iterator();
        feedbacks.add(new Feedback().credits(80.00).type(FeedbackType.MANUAL).detailText("nice submission 1").reference(textBlockIterator.next().getId()));
        feedbacks.add(new Feedback().credits(25.00).type(FeedbackType.MANUAL).detailText("nice submission 2").reference(textBlockIterator.next().getId()));
        var dto = new TextAssessmentDTO();
        dto.setFeedbacks(feedbacks);
        request.putWithResponseBody("/api/text-assessments/text-submissions/" + storedExampleSubmission.getId() + "/example-assessment", dto, Result.class, HttpStatus.OK);
        Result storedResult = resultRepo.findDistinctWithFeedbackBySubmissionId(storedExampleSubmission.getSubmission().getId()).get();
        database.checkFeedbackCorrectlyStored(feedbacks, storedResult.getFeedbacks(), FeedbackType.MANUAL);
        assertThat(storedResult.isExampleResult()).as("stored result is flagged as example result").isTrue();
    }
}
