import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Course } from 'app/entities/course.model';
import { ArtemisDataTableModule } from 'app/shared/data-table/data-table.module';
import { AlertComponent } from 'app/shared/alert/alert.component';
import { MockComponent, MockDirective, MockPipe, MockProvider } from 'ng-mocks';
import { JhiAlertService, JhiTranslateDirective } from 'ng-jhipster';
import { NgbModule } from '@ng-bootstrap/ng-bootstrap';
import { NgxDatatableModule } from '@swimlane/ngx-datatable';
import { FontAwesomeTestingModule } from '@fortawesome/angular-fontawesome/testing';
import { TranslateModule } from '@ngx-translate/core';
import { RouterTestingModule } from '@angular/router/testing';
import * as sinon from 'sinon';
import * as sinonChai from 'sinon-chai';
import * as chai from 'chai';
import { ReactiveFormsModule } from '@angular/forms';
import { MockTranslateValuesDirective } from '../../../course/course-scores/course-scores.component.spec';
import { Exercise, ExerciseType } from 'app/entities/exercise.model';
import { ModelingExercise, UMLDiagramType } from 'app/entities/modeling-exercise.model';
import { ExerciseGroup } from 'app/entities/exercise-group.model';
import { Exam } from 'app/entities/exam.model';
import { StudentParticipation } from 'app/entities/participation/student-participation.model';
import { ParticipationType } from 'app/entities/participation/participation.model';
import { Result } from 'app/entities/result.model';
import { ProgrammingExercise } from 'app/entities/programming-exercise.model';
import { QuizExercise } from 'app/entities/quiz/quiz-exercise.model';
import { FileUploadExercise } from 'app/entities/file-upload-exercise.model';
import { ArtemisTranslatePipe } from 'app/shared/pipes/artemis-translate.pipe.ts';
import { StudentExamDetailTableRowComponent } from 'app/exam/manage/student-exams/student-exam-detail-table-row/student-exam-detail-table-row.component';

chai.use(sinonChai);
const expect = chai.expect;

describe('StudentExamDetailTableRowComponent', () => {
    let studentExamDetailTableRowComponentFixture: ComponentFixture<StudentExamDetailTableRowComponent>;
    let studentExamDetailTableRowComponent: StudentExamDetailTableRowComponent;
    let course: Course;
    let exercise: Exercise;
    let exam1: Exam;
    let studentParticipation: StudentParticipation;
    let result: Result;

    beforeEach(() => {
        course = { id: 1 };
        exam1 = { course, id: 1 };
        result = { score: 40, id: 10 };
        studentParticipation = new StudentParticipation(ParticipationType.STUDENT);
        studentParticipation.results = [result];
        exercise = new ModelingExercise(UMLDiagramType.ActivityDiagram, course, new ExerciseGroup());
        exercise.maxPoints = 100;
        exercise.studentParticipations = [studentParticipation];

        return TestBed.configureTestingModule({
            imports: [
                RouterTestingModule.withRoutes([]),
                ArtemisDataTableModule,
                NgbModule,
                NgxDatatableModule,
                FontAwesomeTestingModule,
                ReactiveFormsModule,
                TranslateModule.forRoot(),
            ],
            declarations: [StudentExamDetailTableRowComponent, MockComponent(AlertComponent), MockTranslateValuesDirective, MockPipe(ArtemisTranslatePipe)],
            providers: [MockProvider(JhiAlertService), MockDirective(JhiTranslateDirective)],
        })
            .compileComponents()
            .then(() => {
                studentExamDetailTableRowComponentFixture = TestBed.createComponent(StudentExamDetailTableRowComponent);
                studentExamDetailTableRowComponent = studentExamDetailTableRowComponentFixture.componentInstance;
            });
    });
    afterEach(() => {
        sinon.restore();
    });

    it('should return the right icon based on exercise type', () => {
        exercise = new ModelingExercise(UMLDiagramType.ClassDiagram, course, new ExerciseGroup());
        expect(studentExamDetailTableRowComponent.getIcon(exercise.type!)).to.equal('project-diagram');

        exercise = new ProgrammingExercise(course, new ExerciseGroup());
        expect(studentExamDetailTableRowComponent.getIcon(exercise.type!)).to.equal('keyboard');

        exercise = new QuizExercise(course, new ExerciseGroup());
        expect(studentExamDetailTableRowComponent.getIcon(exercise.type!)).to.equal('check-double');

        exercise = new FileUploadExercise(course, new ExerciseGroup());
        expect(studentExamDetailTableRowComponent.getIcon(exercise.type!)).to.equal('file-upload');
    });

    it('should route to programming submission dashboard', () => {
        const getAssessmentLinkSpy = sinon.spy(studentExamDetailTableRowComponent, 'getAssessmentLink');
        studentExamDetailTableRowComponentFixture.detectChanges();
        studentExamDetailTableRowComponent.courseId = 23;
        studentExamDetailTableRowComponent.examId = exam1.id!;

        const programmingExercise = {
            numberOfAssessmentsOfCorrectionRounds: [],
            secondCorrectionEnabled: false,
            studentAssignedTeamIdComputed: false,
            id: 12,
            exerciseGroup: { id: 13 },
            type: ExerciseType.PROGRAMMING,
        };
        const route = studentExamDetailTableRowComponent.getAssessmentLink(programmingExercise);
        expect(getAssessmentLinkSpy).to.have.been.calledOnce;
        expect(route).to.deep.equal(['/course-management', '23', 'exams', '1', 'exercise-groups', '13', 'programming-exercises', '12', 'assessment']);
    });

    it('should route to modeling submission', () => {
        const getAssessmentLinkSpy = sinon.spy(studentExamDetailTableRowComponent, 'getAssessmentLink');
        studentExamDetailTableRowComponentFixture.detectChanges();
        studentExamDetailTableRowComponent.courseId = 23;
        studentExamDetailTableRowComponent.examId = exam1.id!;
        const modelingExercise = {
            numberOfAssessmentsOfCorrectionRounds: [],
            secondCorrectionEnabled: false,
            studentAssignedTeamIdComputed: false,
            id: 12,
            type: ExerciseType.MODELING,
            exerciseGroup: { id: 12 },
        };
        const submission = { id: 14 };
        const route = studentExamDetailTableRowComponent.getAssessmentLink(modelingExercise, submission);
        expect(getAssessmentLinkSpy).to.have.been.calledOnce;
        expect(route).to.deep.equal(['/course-management', '23', 'exams', '1', 'exercise-groups', '12', 'modeling-exercises', '12', 'submissions', '14', 'assessment']);
    });
});
