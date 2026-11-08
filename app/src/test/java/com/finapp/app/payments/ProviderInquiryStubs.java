package com.finapp.app.payments;

import com.finapp.payments.EndToEndReference;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.platform.testing.provider.SimulatedProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes the simulated provider's inquiries agree with a callback a suite is about to deliver (`P15-TSK-036`): since a
 * callback adopts nothing of its own, a suite whose subject is what happens AFTER a genuine statement has to give the
 * provider that statement to answer with. A forged-callback test never calls this: there the inquiry disagrees.
 *
 * <p>The answers use the provider's own wire shapes, so a body a suite built to be unreadable stays unreadable here.
 */
public final class ProviderInquiryStubs {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ProviderInquiryStubs() {}

    /** The PSP's inquiries answer what this webhook body says. */
    public static void cardAgrees(SimulatedProvider psp, String webhookBody) {
        JsonNode body = readable(webhookBody);
        if (body == null) {
            return;
        }
        String operation = text(body, "operation");
        String status = text(body, "status");
        if (status == null) {
            return;
        }
        switch (status) {
            case "approved" -> {
                if (operation != null) {
                    psp.succeedsWith(
                            SimulatedCardPspAdapter.OPERATIONS_PATH + operation,
                            200,
                            "{\"status\":\"approved\",\"reference\":\"" + text(body, "reference") + "\"}");
                }
            }
            case "declined" -> {
                if (operation != null) {
                    psp.succeedsWith(
                            SimulatedCardPspAdapter.OPERATIONS_PATH + operation, 200, "{\"status\":\"declined\"}");
                }
            }
            case "cleared" -> {
                if (operation != null) {
                    // The inquiry's contract is the operation's LATEST presentment (ClearingAnswer), so this notice's
                    // references are what an honest PSP answers now, a second presentment's included.
                    psp.succeedsWith(
                            SimulatedCardPspAdapter.OPERATIONS_PATH + operation + SimulatedCardPspAdapter.CLEARING_SUFFIX,
                            200,
                            "{\"status\":\"cleared\",\"arn\":\"" + text(body, "arn")
                                    + "\",\"networkTransactionId\":\"" + text(body, "networkTransactionId") + "\"}");
                }
            }
            case "disputed" -> {
                String dispute = text(body, "dispute");
                if (dispute != null) {
                    // The statement, field for field: the inquiry's wire is the notice's.
                    psp.succeedsWith(SimulatedCardPspAdapter.DISPUTES_PATH + dispute, 200, webhookBody);
                }
            }
            default -> {
                // A word nobody maps asks nothing.
            }
        }
    }

    /** The scheme's inquiries answer what this confirmation says. */
    public static void instantAgrees(SimulatedProvider scheme, String callbackBody) {
        JsonNode body = readable(callbackBody);
        if (body == null) {
            return;
        }
        String reference = text(body, "reference");
        String status = text(body, "status");
        if (status == null) {
            return;
        }
        String schemeReference = text(body, "schemeReference");
        String answer;
        if ("executed".equals(status)) {
            answer = accepted(body, schemeReference, reference);
            if (schemeReference != null) {
                scheme.succeedsWith(SimulatedInstantSchemeAdapter.EXECUTION_STATUS_PATH + schemeReference, 200, answer);
            }
        } else if ("rejected".equals(status) || "expired".equals(status)) {
            answer = "{\"status\":\"" + status + "\"}";
        } else {
            return;
        }
        if (reference != null && shaped(reference)) {
            scheme.succeedsWith(SimulatedInstantSchemeAdapter.INITIATION_STATUS_PATH + reference, 200, answer);
            // The two the door routes by which row carries the reference: the transfers' inquiry says "rejected", never
            // "expired", exactly as its own wire client reads them.
            String transferAnswer = "expired".equals(status) ? "{\"status\":\"rejected\"}" : answer;
            scheme.succeedsWith(SimulatedInstantSchemeAdapter.TRANSFER_STATUS_PATH + reference, 200, transferAnswer);
            scheme.succeedsWith(SimulatedInstantSchemeAdapter.RETURN_STATUS_PATH + reference, 200, transferAnswer);
        }
    }

    private static String accepted(JsonNode body, String schemeReference, String reference) {
        StringBuilder answer = new StringBuilder("{\"status\":\"accepted\"");
        if (schemeReference != null) {
            answer.append(",\"reference\":\"").append(schemeReference).append('"');
        }
        if (reference != null) {
            answer.append(",\"endToEndReference\":\"").append(reference).append('"');
        }
        for (String[] field : new String[][] {{"settlementCycle", "cycle"}, {"amount", "amount"}, {"currency", "currency"}}) {
            String value = text(body, field[0]);
            if (value != null) {
                answer.append(",\"").append(field[1]).append("\":\"").append(value).append('"');
            }
        }
        return answer.append('}').toString();
    }

    private static boolean shaped(String reference) {
        try {
            new EndToEndReference(reference);
            return true;
        } catch (IllegalArgumentException outOfShape) {
            return false;
        }
    }

    private static JsonNode readable(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }
}
