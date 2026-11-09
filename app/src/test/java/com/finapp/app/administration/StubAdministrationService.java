package com.finapp.app.administration;

import com.finapp.identity.IdentityAdministration;
import java.lang.reflect.Proxy;
import javax.sql.DataSource;
import org.springframework.objenesis.SpringObjenesis;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The administration service with its collaborators stood in, for a hermetic test to override (`P15-TSK-039`). Built here
 * and not in the test, so a hermetic suite names no {@code DataSource}, the taxonomy's database signature
 * ({@code InterceptorHarness}'s reason).
 */
abstract class StubAdministrationService extends IdentityAdministrationService {

    StubAdministrationService() {
        super(new SpringObjenesis().newInstance(IdentityAdministration.class), new TransactionTemplate(),
                (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                        (proxy, method, args) -> null));
    }
}
