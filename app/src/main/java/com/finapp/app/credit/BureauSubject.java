package com.finapp.app.credit;

import com.finapp.sharedkernel.money.CountryCode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The identifying facts a bureau matches a person by (`P10-TSK-005`): name, date of birth and
 * country of residence - {@code RESTRICTED-PII}, crossing only the bureau adapter.
 *
 * <p>{@code toString} renders nothing of them.
 *
 * @param fullName the person's legal name
 * @param dateOfBirth their date of birth
 * @param residenceCountry their country of residence
 */
public record BureauSubject(String fullName, LocalDate dateOfBirth, CountryCode residenceCountry) {

    public BureauSubject {
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(dateOfBirth, "dateOfBirth");
        Objects.requireNonNull(residenceCountry, "residenceCountry");
        if (fullName.isBlank()) {
            throw new IllegalArgumentException("a bureau subject has a name");
        }
    }

    @Override
    public String toString() {
        return "BureauSubject[redacted]";
    }
}
