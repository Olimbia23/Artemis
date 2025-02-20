import { NgModule } from '@angular/core';
import { SafeResourceUrlPipe } from 'app/shared/pipes/safe-resource-url.pipe';
import { KeysPipe } from 'app/shared/pipes/keys.pipe';
import { RemoveKeysPipe } from 'app/shared/pipes/remove-keys.pipe';
import { TypeCheckPipe } from 'app/shared/pipes/type-check.pipe';
import { AverageByPipe } from 'app/shared/pipes/average-by.pipe';
import { SafeHtmlPipe } from 'app/shared/pipes/safe-html.pipe';
import { SafeUrlPipe } from 'app/shared/pipes/safe-url.pipe';
import { TruncatePipe } from 'app/shared/pipes/truncate.pipe';
import { SanitizeHtmlPipe } from 'app/shared/pipes/sanitize-html.pipe';
import { ExerciseTypePipe } from 'app/shared/pipes/exercise-type.pipe';
import { ExerciseCourseTitlePipe } from 'app/shared/pipes/exercise-course-title.pipe';

@NgModule({
    declarations: [
        SafeHtmlPipe,
        SafeUrlPipe,
        SafeResourceUrlPipe,
        RemoveKeysPipe,
        ExerciseCourseTitlePipe,
        ExerciseTypePipe,
        KeysPipe,
        TypeCheckPipe,
        TruncatePipe,
        SanitizeHtmlPipe,
        AverageByPipe,
    ],
    exports: [
        SafeHtmlPipe,
        SafeUrlPipe,
        RemoveKeysPipe,
        ExerciseCourseTitlePipe,
        ExerciseTypePipe,
        KeysPipe,
        TypeCheckPipe,
        TruncatePipe,
        SanitizeHtmlPipe,
        SafeResourceUrlPipe,
        AverageByPipe,
    ],
    providers: [SafeResourceUrlPipe],
})
export class ArtemisSharedPipesModule {}
