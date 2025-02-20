package de.tum.in.www1.artemis.web.rest;

import static de.tum.in.www1.artemis.web.rest.util.ResponseUtil.forbidden;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import de.tum.in.www1.artemis.domain.*;
import de.tum.in.www1.artemis.repository.*;
import de.tum.in.www1.artemis.service.AuthorizationCheckService;
import de.tum.in.www1.artemis.service.GroupNotificationService;
import de.tum.in.www1.artemis.service.SingleUserNotificationService;
import de.tum.in.www1.artemis.web.rest.errors.BadRequestAlertException;
import de.tum.in.www1.artemis.web.rest.util.HeaderUtil;
import io.github.jhipster.web.util.ResponseUtil;

/**
 * REST controller for managing StudentQuestionAnswer.
 */
@RestController
@RequestMapping("/api")
public class StudentQuestionAnswerResource {

    private final Logger log = LoggerFactory.getLogger(StudentQuestionAnswerResource.class);

    private static final String ENTITY_NAME = "questionAnswer";

    @Value("${jhipster.clientApp.name}")
    private String applicationName;

    private final StudentQuestionAnswerRepository studentQuestionAnswerRepository;

    private final CourseRepository courseRepository;

    private final StudentQuestionRepository studentQuestionRepository;

    private final AuthorizationCheckService authorizationCheckService;

    private final UserRepository userRepository;

    private final GroupNotificationService groupNotificationService;

    private final SingleUserNotificationService singleUserNotificationService;

    public StudentQuestionAnswerResource(StudentQuestionAnswerRepository studentQuestionAnswerRepository, GroupNotificationService groupNotificationService,
            SingleUserNotificationService singleUserNotificationService, AuthorizationCheckService authorizationCheckService, UserRepository userRepository,
            CourseRepository courseRepository, StudentQuestionRepository studentQuestionRepository) {
        this.studentQuestionAnswerRepository = studentQuestionAnswerRepository;
        this.courseRepository = courseRepository;
        this.studentQuestionRepository = studentQuestionRepository;
        this.groupNotificationService = groupNotificationService;
        this.singleUserNotificationService = singleUserNotificationService;
        this.authorizationCheckService = authorizationCheckService;
        this.userRepository = userRepository;
    }

    /**
     * POST /courses/{courseId}/question-answers : Create a new studentQuestionAnswer.
     *
     * @param courseId the id of the course the answer belongs to
     * @param studentQuestionAnswer the studentQuestionAnswer to create
     * @return the ResponseEntity with status 201 (Created) and with body the new studentQuestionAnswer, or with status 400 (Bad Request) if the studentQuestionAnswer has already
     *         an ID
     * @throws URISyntaxException if the Location URI syntax is incorrect
     */
    @PostMapping("courses/{courseId}/student-question-answers")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<StudentQuestionAnswer> createStudentQuestionAnswer(@PathVariable Long courseId, @RequestBody StudentQuestionAnswer studentQuestionAnswer)
            throws URISyntaxException {
        log.debug("REST request to save StudentQuestionAnswer : {}", studentQuestionAnswer);
        User user = this.userRepository.getUserWithGroupsAndAuthorities();
        if (studentQuestionAnswer.getId() != null) {
            throw new BadRequestAlertException("A new studentQuestionAnswer cannot already have an ID", ENTITY_NAME, "idexists");
        }
        var course = courseRepository.findByIdElseThrow(courseId);
        if (!this.authorizationCheckService.isAtLeastStudentInCourse(course, user)) {
            return forbidden();
        }
        Optional<StudentQuestion> optionalStudentQuestion = studentQuestionRepository.findById(studentQuestionAnswer.getQuestion().getId());
        if (optionalStudentQuestion.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!optionalStudentQuestion.get().getCourse().getId().equals(courseId)) {
            return forbidden();
        }
        // answer to approved if written by an instructor
        studentQuestionAnswer.setTutorApproved(this.authorizationCheckService.isAtLeastInstructorInCourse(course, user));
        StudentQuestionAnswer result = studentQuestionAnswerRepository.save(studentQuestionAnswer);
        if (result.getQuestion().getExercise() != null) {
            groupNotificationService.notifyTutorAndInstructorGroupAboutNewAnswerForExercise(result);
            singleUserNotificationService.notifyUserAboutNewAnswerForExercise(result);
        }
        if (result.getQuestion().getLecture() != null) {
            groupNotificationService.notifyTutorAndInstructorGroupAboutNewAnswerForLecture(result);
            singleUserNotificationService.notifyUserAboutNewAnswerForLecture(result);
        }
        return ResponseEntity.created(new URI("/api/courses" + courseId + "/student-question-answers/" + result.getId()))
                .headers(HeaderUtil.createEntityCreationAlert(applicationName, true, ENTITY_NAME, result.getId().toString())).body(result);
    }

    /**
     * PUT /courses/{courseId}/question-answers : Updates an existing studentQuestionAnswer.
     *
     * @param courseId the id of the course the answer belongs to
     * @param studentQuestionAnswer the studentQuestionAnswer to update
     * @return the ResponseEntity with status 200 (OK) and with body the updated studentQuestionAnswer, or with status 400 (Bad Request) if the studentQuestionAnswer is not valid,
     *         or with status 500 (Internal Server Error) if the studentQuestionAnswer couldn't be updated
     */
    @PutMapping("courses/{courseId}/student-question-answers")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<StudentQuestionAnswer> updateStudentQuestionAnswer(@PathVariable Long courseId, @RequestBody StudentQuestionAnswer studentQuestionAnswer) {
        User user = userRepository.getUserWithGroupsAndAuthorities();
        log.debug("REST request to update StudentQuestionAnswer : {}", studentQuestionAnswer);
        if (studentQuestionAnswer.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        courseRepository.findByIdElseThrow(courseId);
        Optional<StudentQuestionAnswer> optionalStudentQuestionAnswer = studentQuestionAnswerRepository.findById(studentQuestionAnswer.getId());
        if (optionalStudentQuestionAnswer.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!optionalStudentQuestionAnswer.get().getQuestion().getCourse().getId().equals(courseId)) {
            return forbidden();
        }
        if (mayUpdateOrDeleteStudentQuestionAnswer(optionalStudentQuestionAnswer.get(), user)) {
            StudentQuestionAnswer result = studentQuestionAnswerRepository.save(studentQuestionAnswer);
            return ResponseEntity.ok().headers(HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, studentQuestionAnswer.getId().toString())).body(result);
        }
        else {
            return forbidden();
        }
    }

    /**
     * GET /courses/{courseId}/question-answers/:id : get the "id" questionAnswer.
     *
     * @param courseId the id of the course the answer belongs to
     * @param id the id of the questionAnswer to retrieve
     * @return the ResponseEntity with status 200 (OK) and with body the questionAnswer, or with status 404 (Not Found)
     */
    @GetMapping("courses/{courseId}/student-question-answers/{id}")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<StudentQuestionAnswer> getStudentQuestionAnswer(@PathVariable Long courseId, @PathVariable Long id) {
        log.debug("REST request to get StudentQuestionAnswer : {}", id);
        User user = this.userRepository.getUserWithGroupsAndAuthorities();
        var course = courseRepository.findByIdElseThrow(courseId);
        if (!this.authorizationCheckService.isAtLeastStudentInCourse(course, user)) {
            return forbidden();
        }
        Optional<StudentQuestionAnswer> questionAnswer = studentQuestionAnswerRepository.findById(id);
        if (questionAnswer.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!questionAnswer.get().getQuestion().getCourse().getId().equals(courseId)) {
            return forbidden();
        }
        return ResponseUtil.wrapOrNotFound(questionAnswer);
    }

    /**
     * DELETE /courses/{courseId}/question-answers/:id : delete the "id" questionAnswer.
     *
     * @param courseId the id of the course the answer belongs to
     * @param id the id of the questionAnswer to delete
     * @return the ResponseEntity with status 200 (OK)
     */
    @DeleteMapping("courses/{courseId}/student-question-answers/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Void> deleteStudentQuestionAnswer(@PathVariable Long courseId, @PathVariable Long id) {
        User user = userRepository.getUserWithGroupsAndAuthorities();
        Optional<StudentQuestionAnswer> optionalStudentQuestionAnswer = studentQuestionAnswerRepository.findById(id);
        if (optionalStudentQuestionAnswer.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        courseRepository.findByIdElseThrow(courseId);
        StudentQuestionAnswer studentQuestionAnswer = optionalStudentQuestionAnswer.get();
        Course course = studentQuestionAnswer.getQuestion().getCourse();
        String entity = "";
        if (studentQuestionAnswer.getQuestion().getLecture() != null) {
            entity = "lecture with id: " + studentQuestionAnswer.getQuestion().getLecture().getId();
        }
        else if (studentQuestionAnswer.getQuestion().getExercise() != null) {
            entity = "exercise with id: " + studentQuestionAnswer.getQuestion().getExercise().getId();
        }
        if (course == null) {
            return ResponseEntity.badRequest().build();
        }
        if (!course.getId().equals(courseId)) {
            return forbidden();
        }
        if (mayUpdateOrDeleteStudentQuestionAnswer(studentQuestionAnswer, user)) {
            log.info("StudentQuestionAnswer deleted by {}. Answer: {} for {}", user.getLogin(), studentQuestionAnswer.getAnswerText(), entity);
            studentQuestionAnswerRepository.deleteById(id);
            return ResponseEntity.ok().headers(HeaderUtil.createEntityDeletionAlert(applicationName, true, ENTITY_NAME, id.toString())).build();
        }
        else {
            return forbidden();
        }
    }

    /**
     * Check if user can update or delete StudentQuestionAnswer
     *
     * @param studentQuestionAnswer studentQuestionAnswer for which to check
     * @param user user for which to check
     * @return Boolean if StudentQuestionAnswer can updated or deleted
     */
    private boolean mayUpdateOrDeleteStudentQuestionAnswer(StudentQuestionAnswer studentQuestionAnswer, User user) {
        Course course = studentQuestionAnswer.getQuestion().getCourse();
        Boolean hasCourseTAAccess = authorizationCheckService.isAtLeastTeachingAssistantInCourse(course, user);
        Boolean isUserAuthor = user.getId().equals(studentQuestionAnswer.getAuthor().getId());
        return hasCourseTAAccess || isUserAuthor;
    }
}
