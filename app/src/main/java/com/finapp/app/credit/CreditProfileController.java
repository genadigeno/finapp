package com.finapp.app.credit;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import jakarta.servlet.http.HttpServletRequest;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's own credit profile (`P10-TSK-017`): the sources on file with when each last answered, and the
 * decisions still in their validity - never a figure. The session's party is the only party it can name.
 */
@RestController
@RequestMapping(path = "/me/credit", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class CreditProfileController {

    @NonNull private final CreditDecisionRequestDesk desk;

    @GetMapping("/profile")
    public CreditDecisionRequestDesk.CustomerCreditProfileView profile(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return desk.profile(authenticated);
        }
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/credit is reachable without"
                        + " SessionAuthenticationInterceptor having run");
    }
}
