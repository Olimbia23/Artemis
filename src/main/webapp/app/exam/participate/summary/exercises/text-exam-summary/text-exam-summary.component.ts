import { Component, Input, OnInit } from '@angular/core';
import { TextSubmission } from 'app/entities/text-submission.model';

@Component({
    selector: 'jhi-text-exam-summary',
    templateUrl: './text-exam-summary.component.html',
    styles: [
        `
            :host {
                white-space: pre-wrap;
                display: block;
                background-color: #f8f9fa;
            }
        `,
    ],
})
export class TextExamSummaryComponent implements OnInit {
    @Input() submission: TextSubmission | undefined;

    ngOnInit(): void {}
}
