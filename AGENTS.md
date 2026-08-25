Third-party packages are located in the directory specified by the `LY2K_PACKAGES_DIR` environment variable.

The `l2yk` compiler source code is located in `~/Projects/language`.

Create issues for the `l2yk` compiler in https://github.com/y2k/language.

# Engineering Approach

- Implement only the behavior explicitly required now. Do not add speculative features, abstractions, configuration, extension points, or architecture for possible future needs.
- Prefer deleting code, reusing existing code, the standard library, and native platform features. Choose the smallest clear change that works.
- Do not handle hypothetical edge cases. Add handling only for an explicit requirement, a reproduced failure, or a trust-boundary risk involving security or data loss.
- Do not introduce dependencies or boilerplate when a direct implementation is sufficient.
- Mark an intentional shortcut with a `ponytail:` comment that states its limit and when it should be revisited.

#

- After completing code changes, run the relevant tests.
