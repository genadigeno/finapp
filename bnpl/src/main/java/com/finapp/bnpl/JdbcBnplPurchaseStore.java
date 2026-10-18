package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BnplPurchaseStore} over plain JDBC (`P12-TSK-013`, ADR-0033; lending's {@code JdbcLoanStore}): every instant and
 * the purchase's business date the database's - {@code bnpl V006}'s triggers stamp them after the act's locks, whatever
 * is bound is overwritten - every customer read owner-scoped in the statement, every failure its operation and SQLSTATE
 * only ({@link BnplStorageException}).
 */
public final class JdbcBnplPurchaseStore implements BnplPurchaseStore {

    /** The owner's read: the obligation, its purchase, its agreement's latest version and the financed gross. */
    private static final String OWNED = "SELECT o.id AS obligation_id, o.purchase_id, o.agreement_id, o.party_id,"
            + " o.decision_id, o.product, o.plan, o.currency, o.status AS obligation_status, o.purchase_date, o.opened_at,"
            + " o.closure_reason, o.correlation_id,"
            + " p.offer_id, p.checkout_session_id, p.merchant_id, p.status AS purchase_status, p.business_date,"
            + " p.authorised_at, p.financed_at, p.order_id,"
            + " a.version, a.terms_version_id, a.schedule_engine, a.accrual_engine, a.allocation_engine,"
            + " a.refund_allocation_engine, a.fee_schedule_version_id, a.agreement_template_id,"
            + " a.agreement_template_version, a.canonical_format, a.canonical_terms, a.terms_sha256, a.document_sha256,"
            + " a.created_at, a.offer_id AS agreement_offer_id,"
            + " f.scale, f.gross_minor"
            + " FROM bnpl.bnpl_obligation o"
            + " JOIN bnpl.purchase p ON p.id = o.purchase_id"
            + " JOIN bnpl.merchant_financing f ON f.purchase_id = o.purchase_id"
            + " JOIN LATERAL (SELECT * FROM bnpl.bnpl_agreement x WHERE x.obligation_id = o.id"
            + " ORDER BY x.version DESC LIMIT 1) a ON true";

    @Override
    public Authorised insertPurchase(Connection unitOfWork, NewPurchase purchase) {
        Objects.requireNonNull(purchase, "purchase");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                // servicing_zone, business_date and authorised_at are the trigger's.
                "INSERT INTO bnpl.purchase (id, offer_id, checkout_session_id, party_id, merchant_id, product, plan,"
                        + " currency, status, servicing_zone, business_date, authorised_at, correlation_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'AUTHORISED', 'UTC', CAST(clock_timestamp() AS date), clock_timestamp(), ?)"
                        + " RETURNING business_date, authorised_at")) {
            insert.setObject(1, purchase.id().value());
            insert.setObject(2, purchase.offer().value());
            insert.setObject(3, purchase.checkoutSession());
            insert.setObject(4, purchase.party());
            insert.setObject(5, purchase.merchant());
            insert.setString(6, purchase.product().name());
            insert.setString(7, purchase.plan().name());
            insert.setString(8, purchase.currency().code());
            insert.setString(9, purchase.correlation().value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return new Authorised(row.getDate("business_date").toLocalDate(),
                        row.getTimestamp("authorised_at").toInstant());
            }
        } catch (SQLException failure) {
            throw failed("inserting a BNPL purchase", failure);
        }
    }

    @Override
    public Optional<Instant> finance(Connection unitOfWork, BnplPurchaseId purchase, UUID order) {
        Objects.requireNonNull(purchase, "purchase");
        Objects.requireNonNull(order, "order");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE bnpl.purchase SET status = 'FINANCED', order_id = ? WHERE id = ? AND status = 'AUTHORISED'"
                        + " RETURNING financed_at")) {
            update.setObject(1, order);
            update.setObject(2, purchase.value());
            try (ResultSet row = update.executeQuery()) {
                return row.next() ? Optional.of(row.getTimestamp("financed_at").toInstant()) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("financing a BNPL purchase", failure);
        }
    }

    @Override
    public void appendPurchaseEvent(Connection unitOfWork, HistoryRow row) {
        Objects.requireNonNull(row, "row");
        history(unitOfWork, "bnpl.purchase_event", "purchase_id", row.eventId(), row.purchase().value(),
                row.from().map(Enum::name), row.to().name(), row.reason(), row.actor(), row.correlation(),
                "appending a BNPL purchase event");
    }

    @Override
    public void insertFinancing(Connection unitOfWork, MerchantFinancing financing) {
        Objects.requireNonNull(financing, "financing");
        MerchantFeeSplit split = financing.split();
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.merchant_financing (purchase_id, journal_entry_id, merchant_id, currency, scale,"
                        + " gross_minor, fee_minor, net_minor, fee_schedule_version_id, financed_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp())")) {
            insert.setObject(1, financing.purchase().value());
            insert.setObject(2, financing.entry().value());
            insert.setObject(3, financing.merchant());
            insert.setString(4, split.gross().currency().code());
            insert.setInt(5, split.gross().scale());
            insert.setLong(6, split.gross().minorUnits());
            insert.setLong(7, split.fee().minorUnits());
            insert.setLong(8, split.net().minorUnits());
            insert.setObject(9, split.feeScheduleVersionId());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a BNPL merchant financing", failure);
        }
    }

    @Override
    public Instant insertObligation(Connection unitOfWork, NewObligation obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                // servicing_zone, purchase_date, opened_at and next_servicing_at are the trigger's.
                "INSERT INTO bnpl.bnpl_obligation (id, purchase_id, agreement_id, party_id, decision_id, product, plan,"
                        + " currency, status, servicing_zone, purchase_date, opened_at, next_servicing_at, correlation_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 'UTC', CAST(clock_timestamp() AS date), clock_timestamp(),"
                        + " clock_timestamp(), ?) RETURNING opened_at")) {
            insert.setObject(1, obligation.id().value());
            insert.setObject(2, obligation.purchase().value());
            insert.setObject(3, obligation.agreement().value());
            insert.setObject(4, obligation.party());
            insert.setObject(5, obligation.decision());
            insert.setString(6, obligation.product().name());
            insert.setString(7, obligation.plan().name());
            insert.setString(8, obligation.currency().code());
            insert.setString(9, obligation.correlation().value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp("opened_at").toInstant();
            }
        } catch (SQLException failure) {
            throw failed("inserting a BNPL obligation", failure);
        }
    }

    @Override
    public void appendObligationEvent(Connection unitOfWork, ObligationHistoryRow row) {
        Objects.requireNonNull(row, "row");
        history(unitOfWork, "bnpl.bnpl_obligation_event", "obligation_id", row.eventId(), row.obligation().value(),
                row.from().map(Enum::name), row.to().name(), row.reason(), row.actor(), row.correlation(),
                "appending a BNPL obligation event");
    }

    @Override
    public Instant insertAgreement(Connection unitOfWork, BnplAgreement agreement) {
        Objects.requireNonNull(agreement, "agreement");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                // effective_from and created_at are the trigger's.
                "INSERT INTO bnpl.bnpl_agreement (agreement_id, version, obligation_id, offer_id, decision_id,"
                        + " terms_version_id, product, plan, schedule_engine, accrual_engine, allocation_engine,"
                        + " refund_allocation_engine, fee_schedule_version_id, agreement_template_id,"
                        + " agreement_template_version, canonical_format, canonical_terms, terms_sha256, document_sha256,"
                        + " effective_from, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(),"
                        + " clock_timestamp()) RETURNING created_at")) {
            insert.setObject(1, agreement.id().value());
            insert.setInt(2, agreement.version());
            insert.setObject(3, agreement.obligation().value());
            insert.setObject(4, agreement.offer().value());
            insert.setObject(5, agreement.decision());
            insert.setObject(6, agreement.termsVersionId().value());
            insert.setString(7, agreement.product().name());
            insert.setString(8, agreement.plan().name());
            insert.setString(9, agreement.scheduleEngine());
            insert.setString(10, agreement.accrualEngine().orElse(null));
            insert.setString(11, agreement.allocationEngine());
            insert.setString(12, agreement.refundAllocationEngine());
            insert.setObject(13, agreement.feeScheduleVersion());
            insert.setString(14, agreement.template().id());
            insert.setInt(15, agreement.template().version());
            insert.setInt(16, agreement.canonicalFormat());
            insert.setString(17, agreement.canonicalTerms());
            insert.setBytes(18, agreement.termsSha256());
            insert.setBytes(19, agreement.documentSha256());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp("created_at").toInstant();
            }
        } catch (SQLException failure) {
            throw failed("inserting a BNPL agreement", failure);
        }
    }

    @Override
    public Instant insertAcceptance(Connection unitOfWork, UUID id, BnplAgreement agreement,
            BnplAcceptanceEvidence evidence, CorrelationId correlation) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(correlation, "correlation");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_acceptance (id, agreement_id, agreement_version, offer_id, terms_sha256,"
                        + " document_sha256, identity_id, session_id, assurance, channel, client_ip, user_agent,"
                        + " accepted_at, correlation_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS inet), ?, clock_timestamp(), ?)"
                        + " RETURNING accepted_at")) {
            insert.setObject(1, id);
            insert.setObject(2, agreement.id().value());
            insert.setInt(3, agreement.version());
            insert.setObject(4, agreement.offer().value());
            insert.setBytes(5, agreement.termsSha256());
            insert.setBytes(6, agreement.documentSha256());
            insert.setObject(7, evidence.identity());
            insert.setObject(8, evidence.session());
            insert.setString(9, evidence.assuranceLevel());
            insert.setString(10, BnplAcceptanceEvidence.CHANNEL);
            insert.setString(11, evidence.clientIp().orElse(null));
            insert.setString(12, evidence.userAgent().orElse(null));
            insert.setString(13, correlation.value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp("accepted_at").toInstant();
            }
        } catch (SQLException failure) {
            throw failed("inserting a BNPL acceptance's evidence", failure);
        }
    }

    @Override
    public void insertSchedule(Connection unitOfWork, StoredSchedule schedule) {
        Objects.requireNonNull(schedule, "schedule");
        Money principal = schedule.principal();
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_schedule (id, obligation_id, schedule_version, engine, currency, scale,"
                        + " principal_minor, start_date, instalment_count, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp())")) {
            insert.setObject(1, schedule.id());
            insert.setObject(2, schedule.obligation().value());
            insert.setInt(3, schedule.version());
            insert.setString(4, schedule.engine());
            insert.setString(5, principal.currency().code());
            insert.setInt(6, principal.scale());
            insert.setLong(7, principal.minorUnits());
            insert.setDate(8, Date.valueOf(schedule.start()));
            insert.setInt(9, schedule.instalments().size());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a BNPL schedule", failure);
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_instalment (id, schedule_id, obligation_id, sequence, due_date, currency, scale,"
                        + " principal_minor, interest_minor, amount_minor) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (StoredInstalment instalment : schedule.instalments()) {
                insert.setObject(1, instalment.id());
                insert.setObject(2, schedule.id());
                insert.setObject(3, schedule.obligation().value());
                insert.setInt(4, instalment.sequence());
                insert.setDate(5, Date.valueOf(instalment.dueDate()));
                insert.setString(6, instalment.amount().currency().code());
                insert.setInt(7, instalment.amount().scale());
                insert.setLong(8, instalment.principal().minorUnits());
                insert.setLong(9, instalment.interest().minorUnits());
                insert.setLong(10, instalment.amount().minorUnits());
                insert.executeUpdate();
            }
        } catch (SQLException failure) {
            throw failed("inserting a BNPL schedule's instalments", failure);
        }
    }

    @Override
    public void insertBilling(Connection unitOfWork, Billing billing) {
        Objects.requireNonNull(billing, "billing");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_billing (id, instalment_id, obligation_id, value_date, currency, scale,"
                        + " principal_minor, interest_minor, journal_entry_id, billed_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp())")) {
            insert.setObject(1, billing.id());
            insert.setObject(2, billing.instalment());
            insert.setObject(3, billing.obligation().value());
            insert.setDate(4, Date.valueOf(billing.valueDate()));
            insert.setString(5, billing.principal().currency().code());
            insert.setInt(6, billing.principal().scale());
            insert.setLong(7, billing.principal().minorUnits());
            insert.setLong(8, billing.interest().minorUnits());
            insert.setObject(9, billing.entry().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a BNPL billing", failure);
        }
    }

    @Override
    public void insertRepayment(Connection unitOfWork, Repayment repayment) {
        Objects.requireNonNull(repayment, "repayment");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_repayment (id, obligation_id, kind, request_key, currency, scale, amount_minor,"
                        + " agreement_id, agreement_version, allocation_engine, journal_entry_id, value_date, received_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp())")) {
            insert.setObject(1, repayment.id());
            insert.setObject(2, repayment.obligation().value());
            insert.setString(3, repayment.kind());
            insert.setString(4, repayment.requestKey());
            insert.setString(5, repayment.amount().currency().code());
            insert.setInt(6, repayment.amount().scale());
            insert.setLong(7, repayment.amount().minorUnits());
            insert.setObject(8, repayment.agreement().value());
            insert.setInt(9, repayment.agreementVersion());
            insert.setString(10, repayment.allocationEngine());
            insert.setObject(11, repayment.entry().value());
            insert.setDate(12, Date.valueOf(repayment.valueDate()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a BNPL repayment", failure);
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_repayment_allocation (id, repayment_id, sequence, target, instalment_id,"
                        + " component, currency, scale, amount_minor) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (Allocation line : repayment.allocations()) {
                insert.setObject(1, line.id());
                insert.setObject(2, repayment.id());
                insert.setInt(3, line.sequence());
                insert.setString(4, line.target());
                if (line.instalment().isPresent()) {
                    insert.setObject(5, line.instalment().get());
                } else {
                    insert.setNull(5, Types.OTHER);
                }
                insert.setString(6, line.component().orElse(null));
                insert.setString(7, line.amount().currency().code());
                insert.setInt(8, line.amount().scale());
                insert.setLong(9, line.amount().minorUnits());
                insert.executeUpdate();
            }
        } catch (SQLException failure) {
            throw failed("inserting a BNPL repayment's allocation", failure);
        }
    }

    // ------------------------------------------------------------------ reads

    @Override
    public List<Owned> ownedBy(Connection unitOfWork, UUID party, int limit) {
        Objects.requireNonNull(party, "party");
        if (limit < 1) {
            throw new IllegalArgumentException("a list reads at least one agreement");
        }
        try (PreparedStatement select = unitOfWork.prepareStatement(
                OWNED + " WHERE o.party_id = ? ORDER BY o.opened_at DESC, o.id LIMIT ?")) {
            select.setObject(1, party);
            select.setInt(2, limit);
            List<Owned> owned = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    owned.add(owned(rows));
                }
            }
            return owned;
        } catch (SQLException failure) {
            throw failed("listing a party's BNPL agreements", failure);
        }
    }

    @Override
    public Optional<Owned> agreementOwnedBy(Connection unitOfWork, BnplAgreementId id, UUID party) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                OWNED + " WHERE o.agreement_id = ? AND o.party_id = ?")) {
            select.setObject(1, id.value());
            select.setObject(2, party);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(owned(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading a BNPL agreement", failure);
        }
    }

    @Override
    public Optional<StoredSchedule> schedule(Connection unitOfWork, BnplObligationId obligation, int version) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT s.id, s.engine, s.currency, s.scale, s.principal_minor, s.start_date, i.id AS instalment_id,"
                        + " i.sequence, i.due_date, i.principal_minor AS instalment_principal, i.interest_minor,"
                        + " i.amount_minor FROM bnpl.bnpl_schedule s JOIN bnpl.bnpl_instalment i ON i.schedule_id = s.id"
                        + " WHERE s.obligation_id = ? AND s.schedule_version = ? ORDER BY i.sequence")) {
            select.setObject(1, obligation.value());
            select.setInt(2, version);
            UUID id = null;
            String engine = null;
            Money principal = null;
            java.time.LocalDate start = null;
            List<StoredInstalment> instalments = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    CurrencyCode currency = CurrencyCode.of(rows.getString("currency").strip());
                    int scale = rows.getInt("scale");
                    if (id == null) {
                        id = rows.getObject("id", UUID.class);
                        engine = rows.getString("engine");
                        principal = Money.ofPersisted(rows.getLong("principal_minor"), currency, scale);
                        start = rows.getDate("start_date").toLocalDate();
                    }
                    instalments.add(new StoredInstalment(rows.getObject("instalment_id", UUID.class),
                            rows.getInt("sequence"), rows.getDate("due_date").toLocalDate(),
                            Money.ofPersisted(rows.getLong("instalment_principal"), currency, scale),
                            Money.ofPersisted(rows.getLong("interest_minor"), currency, scale),
                            Money.ofPersisted(rows.getLong("amount_minor"), currency, scale)));
                }
            }
            return id == null ? Optional.empty()
                    : Optional.of(new StoredSchedule(id, obligation, version, engine, principal, start, instalments));
        } catch (SQLException failure) {
            throw failed("reading a BNPL schedule", failure);
        }
    }

    @Override
    public List<UUID> billedInstalments(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT instalment_id FROM bnpl.bnpl_billing WHERE obligation_id = ? ORDER BY value_date, instalment_id")) {
            select.setObject(1, obligation.value());
            List<UUID> billed = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    billed.add(rows.getObject("instalment_id", UUID.class));
                }
            }
            return billed;
        } catch (SQLException failure) {
            throw failed("reading a BNPL obligation's billings", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Owned owned(ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
        BnplProduct product = BnplProduct.valueOf(row.getString("product"));
        BnplPlan plan = BnplPlan.valueOf(row.getString("plan"));
        CorrelationId correlation = CorrelationId.of(row.getString("correlation_id"));
        BnplObligationId obligationId = BnplObligationId.of(row.getObject("obligation_id", UUID.class));
        BnplPurchaseId purchaseId = BnplPurchaseId.of(row.getObject("purchase_id", UUID.class));
        BnplAgreementId agreementId = BnplAgreementId.of(row.getObject("agreement_id", UUID.class));
        UUID party = row.getObject("party_id", UUID.class);
        UUID decision = row.getObject("decision_id", UUID.class);
        String closure = row.getString("closure_reason");
        BnplObligation obligation = new BnplObligation(obligationId, purchaseId, agreementId, party, decision, product,
                plan, currency, BnplObligationStatus.valueOf(row.getString("obligation_status")),
                row.getDate("purchase_date").toLocalDate(), row.getTimestamp("opened_at").toInstant(),
                Optional.ofNullable(closure).map(BnplClosureReason::valueOf), correlation);
        Timestamp financed = row.getTimestamp("financed_at");
        BnplPurchase purchase = new BnplPurchase(purchaseId, BnplOfferId.of(row.getObject("offer_id", UUID.class)),
                row.getObject("checkout_session_id", UUID.class), party, row.getObject("merchant_id", UUID.class),
                product, plan, currency, BnplPurchaseStatus.valueOf(row.getString("purchase_status")),
                row.getDate("business_date").toLocalDate(), row.getTimestamp("authorised_at").toInstant(),
                Optional.ofNullable(financed).map(Timestamp::toInstant),
                Optional.ofNullable(row.getObject("order_id", UUID.class)), correlation);
        BnplAgreementTemplate template = BnplAgreementTemplate.of(row.getString("agreement_template_id"),
                        row.getInt("agreement_template_version"))
                .orElseThrow(() -> new IllegalStateException("an agreement names a registered template"));
        BnplAgreement agreement = new BnplAgreement(agreementId, row.getInt("version"), obligationId,
                BnplOfferId.of(row.getObject("agreement_offer_id", UUID.class)), decision,
                BnplTermsVersionId.of(row.getObject("terms_version_id", UUID.class)), product, plan,
                row.getString("schedule_engine"), Optional.ofNullable(row.getString("accrual_engine")),
                row.getString("allocation_engine"), row.getString("refund_allocation_engine"),
                row.getObject("fee_schedule_version_id", UUID.class), template, row.getInt("canonical_format"),
                row.getString("canonical_terms"), row.getBytes("terms_sha256"), row.getBytes("document_sha256"),
                row.getTimestamp("created_at").toInstant());
        Money gross = Money.ofPersisted(row.getLong("gross_minor"), currency, row.getInt("scale"));
        return new Owned(obligation, purchase, agreement, gross);
    }

    private static void history(Connection unitOfWork, String table, String key, UUID eventId, UUID subject,
            Optional<String> from, String to, String reason, com.finapp.platform.security.Actor actor,
            CorrelationId correlation, String operation) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO " + table + " (id, " + key + ", from_status, to_status, reason, actor, actor_type,"
                        + " occurred_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)")) {
            insert.setObject(1, eventId);
            insert.setObject(2, subject);
            insert.setString(3, from.orElse(null));
            insert.setString(4, to);
            insert.setString(5, reason);
            insert.setString(6, actor.id());
            insert.setString(7, actor.type().name());
            insert.setString(8, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static BnplStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new BnplStorageException("bnpl storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
