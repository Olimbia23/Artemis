import { Component, EventEmitter, HostBinding, Input, Output } from '@angular/core';
import { Result } from 'app/entities/result.model';
import { ComplaintResponse } from 'app/entities/complaint-response.model';
import { Complaint, ComplaintType } from 'app/entities/complaint.model';
import { Exercise } from 'app/entities/exercise.model';
import { Submission } from 'app/entities/submission.model';

/**
 * The <jhi-assessment-layout> component provides the basic layout for an assessment page.
 * It shows the header, alerts for complaints on top and the complaint form at the bottom of the page.
 * The actual assessment needs to be inserted using content projection.
 * Components using this component need to provide Inputs and handle Outputs. This component does not perform assessment logic.
 */
@Component({
    selector: 'jhi-assessment-layout',
    templateUrl: './assessment-layout.component.html',
    styleUrls: ['./assessment-layout.component.scss'],
})
export class AssessmentLayoutComponent {
    @HostBinding('class.assessment-container') readonly assessmentContainerClass = true;

    @Output() navigateBack = new EventEmitter<void>();
    MORE_FEEDBACK = ComplaintType.MORE_FEEDBACK;
    @Input() isLoading: boolean;
    @Input() saveBusy: boolean;
    @Input() submitBusy: boolean;
    @Input() cancelBusy: boolean;
    @Input() nextSubmissionBusy: boolean;

    @Input() isTeamMode: boolean;
    @Input() isAssessor: boolean;
    @Input() isAtLeastInstructor: boolean;
    @Input() canOverride: boolean;
    @Input() isTestRun = false;
    @Input() isIllegalSubmission: boolean;
    @Input() exerciseDashboardLink: string[];

    @Input() result?: Result;
    @Input() assessmentsAreValid: boolean;
    @Input() complaint?: Complaint;
    @Input() exercise?: Exercise;
    @Input() submission?: Submission;
    @Input() hasAssessmentDueDatePassed: boolean;

    @Output() save = new EventEmitter<void>();
    @Output() submit = new EventEmitter<void>();
    @Output() cancel = new EventEmitter<void>();
    @Output() nextSubmission = new EventEmitter<void>();
    @Output() updateAssessmentAfterComplaint = new EventEmitter<ComplaintResponse>();
}
