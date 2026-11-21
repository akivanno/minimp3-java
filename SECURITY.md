# Security

This library decodes untrusted data: files that any user can upload. It must never throw for the
content of the data, read outside the range it is given, loop forever or allocate in proportion to
values read from the data.

If you find an input that breaks one of these guarantees, please report it privately through
GitHub's **Report a vulnerability** button (Security tab of the repository) instead of opening a public
issue. Include the input (or how to build it) and the Java version.

Problems that also affect the C original are worth reporting to
[minimp3](https://github.com/lieff/minimp3) as well, once they are fixed here.
