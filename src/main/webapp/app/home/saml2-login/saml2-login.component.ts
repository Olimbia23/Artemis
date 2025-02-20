import { Component, Input, OnInit } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { LoginService } from 'app/core/login/login.service';
import { JhiEventManager } from 'ng-jhipster';
import { Saml2Config } from 'app/home/saml2-login/saml2.config';

@Component({
    selector: 'jhi-saml2-login',
    templateUrl: './saml2-login.component.html',
    styles: [],
})
export class Saml2LoginComponent implements OnInit {
    @Input()
    rememberMe = true;
    @Input()
    acceptTerms = false;
    @Input()
    saml2Profile: Saml2Config;

    constructor(private loginService: LoginService, private eventManager: JhiEventManager) {}

    ngOnInit(): void {
        // If SAML2 flow was started, retry login.
        if (document.cookie.indexOf('SAML2flow=') >= 0) {
            // remove cookie
            document.cookie = 'SAML2flow=; expires=Thu, 01 Jan 1970 00:00:00 UTC; ; SameSite=Lax;';
            this.loginSAML2();
        }
    }

    loginSAML2() {
        this.loginService
            .loginSAML2(this.rememberMe)
            .then(() => {
                this.eventManager.broadcast({
                    name: 'authenticationSuccess',
                    content: 'Sending Authentication Success',
                });
            })
            .catch((error: HttpErrorResponse) => {
                if (error.status === 401) {
                    // (re)set cookie
                    document.cookie = 'SAML2flow=true; max-age=120; SameSite=Lax;';
                    // arbitrary by SAML2 HTTP Filter Chain secured URL
                    window.location.replace('/saml2/authenticate');
                }
            });
    }
}
