package com.finapp.merchant;

import static com.finapp.platform.sourcejoin.SourceDoor.POSTING;

import com.finapp.platform.sourcejoin.JournalSources;
import com.finapp.platform.sourcejoin.Keys;
import com.finapp.platform.sourcejoin.SourceFigures;
import com.finapp.platform.sourcejoin.SourceJoin;
import com.finapp.platform.sourcejoin.SqlSourceJoin;
import java.util.List;

/**
 * The {@code merchant} module's read port for the source-join census (`P15-TSK-035`): a payout and a payout return, each joined
 * to its own row.
 */
public final class MerchantJournalSources implements JournalSources {

    static final String PAYOUT = "SELECT id::text AS key_text, amount_minor, currency, scale FROM merchant.merchant_payout WHERE id = ANY (?)";
    static final String PAYOUT_RETURN = "SELECT payout_id::text AS key_text, amount_minor, currency, scale FROM merchant.payout_return"
            + " WHERE payout_id = ANY (?)";

    private static final List<SourceJoin> JOINS = List.of(
            new SqlSourceJoin(POSTING, "merchant-payout:", "merchant.merchant_payout by the payout id: debits and credits equal its amount",
                    PAYOUT, Keys.suffixUuid(),
                    row -> SourceFigures.total(row.getString("currency").trim(), row.getInt("scale"), row.getLong("amount_minor"))),
            new SqlSourceJoin(POSTING, "merchant-payout-return:", "merchant.payout_return by the payout id: debits and credits equal the return's amount",
                    PAYOUT_RETURN, Keys.suffixUuid(),
                    row -> SourceFigures.total(row.getString("currency").trim(), row.getInt("scale"), row.getLong("amount_minor"))));

    @Override
    public String module() {
        return "merchant";
    }

    @Override
    public List<SourceJoin> joins() {
        return JOINS;
    }
}
