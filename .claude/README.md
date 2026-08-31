# Claude Code configuration

The root `CLAUDE.md` contains project-wide instructions.

`.claude/rules/` contains focused rules. Path-scoped rules load when Claude is working with matching files, reducing unnecessary context.

Keep global instructions concise. Put domain-specific procedures and requirements in focused rules or documentation.

Use `/memory` in Claude Code to inspect loaded memory and `/context` to inspect context usage.
