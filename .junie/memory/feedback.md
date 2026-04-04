[2026-04-01 17:41] - Updated by Junie
{
    "TYPE": "preference",
    "CATEGORY": "Commit workflow",
    "EXPECTATION": "User wants commits made whenever a step is considered done/good.",
    "NEW INSTRUCTION": "WHEN a development step is completed successfully THEN commit the changes immediately"
}

[2026-04-01 17:47] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Clarity of error",
    "EXPECTATION": "User wants clear, plain-French explanations instead of opaque internal status messages like 'Failed to pass user review after 6 attempts'.",
    "NEW INSTRUCTION": "WHEN an internal/system error or status is shown THEN translate it to plain French and explain cause, impact, and next steps"
}

[2026-04-01 17:48] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Error message clarity",
    "EXPECTATION": "The user wants the opaque status message translated and explained plainly in French with cause, impact, and next steps.",
    "NEW INSTRUCTION": "WHEN showing internal review/status failure messages THEN explain in plain French cause, impact, and proposed next steps"
}

[2026-04-01 17:56] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Error message clarity",
    "EXPECTATION": "L'utilisateur veut une explication claire en français de « Failed to pass user review after 6 attempts » avec la cause, l’impact et les prochaines étapes.",
    "NEW INSTRUCTION": "WHEN an internal review/status message is shown THEN explain in plain French with cause, impact, next steps"
}

[2026-04-01 19:54] - Updated by Junie
{
    "TYPE": "preference",
    "CATEGORY": "Commit workflow",
    "EXPECTATION": "User wants commits made as soon as the code is stable after a step.",
    "NEW INSTRUCTION": "WHEN code is stable after completing a step THEN commit changes immediately"
}

[2026-04-03 10:51] - Updated by Junie
{
    "TYPE": "preference",
    "CATEGORY": "Commit workflow",
    "EXPECTATION": "User wants an immediate commit at each stable step with a proper message.",
    "NEW INSTRUCTION": "WHEN code is stable after a step THEN commit immediately with a descriptive message"
}

[2026-04-03 15:42] - Updated by Junie
{
    "TYPE": "preference",
    "CATEGORY": "Commit workflow override",
    "EXPECTATION": "Do not commit after fixing ParameterizedEventTest; only report success.",
    "NEW INSTRUCTION": "WHEN ParameterizedEventTest passes locally THEN do not commit and just report success"
}

[2026-04-03 18:49] - Updated by Junie
{
    "TYPE": "preference",
    "CATEGORY": "Commit workflow",
    "EXPECTATION": "User wants an immediate commit with a good message after each stable step, then continue.",
    "NEW INSTRUCTION": "WHEN a development step is stable THEN commit immediately with a descriptive message"
}

[2026-04-03 18:58] - Updated by Junie
{
    "TYPE": "preference",
    "CATEGORY": "Commit workflow",
    "EXPECTATION": "User wants an immediate commit with a good message after each stable step, then continue.",
    "NEW INSTRUCTION": "WHEN a development step is stable THEN commit immediately with descriptive message and continue"
}

