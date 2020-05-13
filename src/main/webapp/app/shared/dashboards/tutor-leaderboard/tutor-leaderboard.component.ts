import { Component, Input, OnInit } from '@angular/core';
import { TutorLeaderboardElement } from 'app/shared/dashboards/tutor-leaderboard/tutor-leaderboard.model';
import { Course } from 'app/entities/course.model';
import { Exercise } from 'app/entities/exercise.model';
import { AccountService } from 'app/core/auth/account.service';

@Component({
    selector: 'jhi-tutor-leaderboard',
    templateUrl: './tutor-leaderboard.component.html',
})
export class TutorLeaderboardComponent implements OnInit {
    @Input() public tutorsData: TutorLeaderboardElement[] = [];
    @Input() public course?: Course;
    @Input() public exercise?: Exercise;

    isAtLeastInstructor = false;

    sortPredicate = 'points';
    reverseOrder = false;

    constructor(private accountService: AccountService) {}

    /**
     * Life cycle hook called by Angular on initialisation. It sets {@link isAtLeastInstructor} if the user has instructor rights for the {@link course}.
     * See {@link accountService~isAtLeastInstructorInCourse}.
     */
    ngOnInit(): void {
        if (this.course) {
            this.isAtLeastInstructor = this.accountService.isAtLeastInstructorInCourse(this.course);
        }
        if (this.exercise && this.exercise.course) {
            this.isAtLeastInstructor = this.accountService.isAtLeastInstructorInCourse(this.exercise.course);
        }
    }

    /**
     * Empty callback
     * @callback
     */
    callback() {}
}
