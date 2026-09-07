package com.finapp.app.mfa;

import com.finapp.identity.MfaEnrolmentService;
import com.finapp.identity.Session;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transaction boundary for MFA enrolment (`P1-TSK-017`).
 *
 * <p>Takes the proven {@link Session}, never an {@code IdentityId} — the {@code P1-TSK-016} shape,
 * and for the same reason: an identifier parameter would be satisfied just as well by one read out
 * of the request, which is the defect ADR-0031 names.
 *
 * <p>One transaction per operation, so the enrolment, its audit record and (on confirmation) its
 * outbox row commit together or not at all ({@code INV-EVT-01}).
 */
@Service
public class MfaEnrolmentApplicationService {

    /** The label a customer sees in their authenticator app. */
    private static final String ISSUER = "finapp";

    private final MfaEnrolmentService enrolments;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public MfaEnrolmentApplicationService(
            MfaEnrolmentService enrolments,
            TransactionTemplate mfaTransactions,
            DataSource dataSource) {
        this.enrolments = Objects.requireNonNull(enrolments, "enrolments must not be null");
        this.transactions = Objects.requireNonNull(mfaTransactions, "mfaTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /**
     * Begins an enrolment and renders the one response that carries the secret.
     *
     * @return empty when a confirmed factor already exists and this session is not assured enough
     *     to replace it — `P1-TSK-019`'s finding
     */
    public java.util.Optional<MfaEnrolmentStarted> begin(Session current) {
        Objects.requireNonNull(current, "current must not be null");

        java.util.Optional<MfaEnrolmentService.Started> started =
                inATransaction(
                        unitOfWork ->
                                enrolments.begin(
                                        unitOfWork, current.identityId(), current.assurance()));

        // The one moment the secret leaves the server, and it leaves inside the URI - which is
        // what a QR code is. There is no read path that returns it afterwards.
        return Objects.requireNonNull(started, "the transaction returned no outcome")
                .map(
                        result ->
                                new MfaEnrolmentStarted(
                                        provisioningUri(
                                                result.secret().expose(), current, result.parameters()),
                                        result.parameters().digits(),
                                        result.parameters().periodSeconds()));
    }

    /** Confirms it. False for every reason, so none of them is distinguishable. */
    public boolean confirm(Session current, String code) {
        Objects.requireNonNull(current, "current must not be null");

        return Boolean.TRUE.equals(
                inATransaction(
                        unitOfWork ->
                                enrolments.confirm(unitOfWork, current.identityId(), code)));
    }

    /**
     * The {@code otpauth://} URI an authenticator app scans.
     *
     * <p>The account label is the <strong>identity identifier</strong> rather than a login
     * identifier or an email address. That is deliberate and slightly unfriendly: the label is
     * rendered in the app, screenshotted into support tickets and synced to whatever backs the app
     * up, so putting a login identifier there would export a {@code CONFIDENTIAL} value (it carries
     * existence) into places this platform has no reach over.
     */
    private static String provisioningUri(
            String secret, Session current, com.finapp.identity.TotpParameters parameters) {
        String label = encode(ISSUER + ":" + current.identityId().value());
        return "otpauth://totp/"
                + label
                + "?secret=" + secret
                + "&issuer=" + encode(ISSUER)
                + "&algorithm=" + parameters.algorithm().name()
                + "&digits=" + parameters.digits()
                + "&period=" + parameters.periodSeconds();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private <T> T inATransaction(java.util.function.Function<Connection, T> work) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
