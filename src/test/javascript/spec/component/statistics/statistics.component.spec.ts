import * as chai from 'chai';
import * as sinonChai from 'sinon-chai';
import * as sinon from 'sinon';
import { ComponentFixture, fakeAsync, TestBed, tick } from '@angular/core/testing';
import { ArtemisTestModule } from '../../test.module';
import { MockSyncStorage } from '../../helpers/mocks/service/mock-sync-storage.service';
import { LocalStorageService, SessionStorageService } from 'ngx-webstorage';
import { MockComponent, MockDirective, MockPipe } from 'ng-mocks';
import { AlertComponent } from 'app/shared/alert/alert.component';
import { RouterTestingModule } from '@angular/router/testing';
import { MockHasAnyAuthorityDirective } from '../../helpers/mocks/directive/mock-has-any-authority.directive';
import { ArtemisTranslatePipe } from 'app/shared/pipes/artemis-translate.pipe.ts';
import { ArtemisDatePipe } from 'app/shared/pipes/artemis-date.pipe';
import { MomentModule } from 'ngx-moment';
import { StatisticsComponent } from 'app/admin/statistics/statistics.component';
import { StatisticsGraphComponent } from 'app/shared/statistics-graph/statistics-graph.component';
import { SpanType } from 'app/entities/statistics.model';

chai.use(sinonChai);
const expect = chai.expect;

describe('StatisticsComponent', () => {
    let fixture: ComponentFixture<StatisticsComponent>;
    let component: StatisticsComponent;

    beforeEach(fakeAsync(() => {
        TestBed.configureTestingModule({
            imports: [ArtemisTestModule, RouterTestingModule.withRoutes([]), MomentModule],
            declarations: [
                StatisticsComponent,
                MockComponent(AlertComponent),
                MockComponent(StatisticsGraphComponent),
                MockDirective(MockHasAnyAuthorityDirective),
                MockPipe(ArtemisTranslatePipe),
                MockPipe(ArtemisDatePipe),
            ],
            providers: [
                { provide: LocalStorageService, useClass: MockSyncStorage },
                { provide: SessionStorageService, useClass: MockSyncStorage },
            ],
        })
            .compileComponents()
            .then(() => {
                fixture = TestBed.createComponent(StatisticsComponent);
                component = fixture.componentInstance;
            });
    }));

    afterEach(fakeAsync(() => {
        jest.clearAllMocks();
    }));

    it('should initialize', fakeAsync(() => {
        fixture.detectChanges();
        expect(component).to.be.ok;
    }));

    it('should click Month button', fakeAsync(() => {
        const tabSpy = sinon.spy(component, 'onTabChanged');
        fixture.detectChanges();

        const button = fixture.debugElement.nativeElement.querySelector('#option3');
        button.click();

        tick();
        expect(tabSpy).to.have.been.calledOnce;
        expect(component.currentSpan).to.be.equal(SpanType.MONTH);
        tabSpy.restore();
    }));
});
