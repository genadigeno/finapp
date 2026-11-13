package com.finapp.app.shedding;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The command's deadline (`P16-TSK-016`, ADR-0137): registered after the session's and the merchant key's authentication and before the
 * per-source count, so it reads the time those lookups took and refuses before the handler - before the claim - and before a count is
 * spent. A command whose request has less than one borrow's wait left of its deadline is shed ({@link LoadShedding#requireTimeLeft}).
 *
 * <p>It decides only for what the door admitted: a request the door exempted or never saw carries no admission and passes. What a
 * command shed here leaves behind is only what authentication writes for every request it admits (a session's idle bound moved
 * forward), never a row of the command.
 */
public final class ShedOnTheDeadline implements HandlerInterceptor {

    private final LoadShedding shedding;

    public ShedOnTheDeadline(LoadShedding shedding) {
        this.shedding = Objects.requireNonNull(shedding, "shedding must not be null");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getAttribute(ShedAtTheDoor.ADMISSION) instanceof LoadShedding.Admission admission) {
            shedding.requireTimeLeft(admission);
        }
        return true;
    }
}
