# NextAlarm: project rules

NextAlarm is an Android-only alarm clock that is controlled by the user's calendars.
The target device is the **Google Pixel 10a**. There is no iPhone/iOS variant.
See [README.md](../README.md) and [docs/](../docs/) for the concept and architecture.

## Git

- Always delete local stale branches once they have been merged:
  - after a merge, delete the merged branch locally (`git branch -d <branch>`);
  - run `git fetch --prune` and delete every local branch listed by `git branch --merged main` (except `main`)
    as well as local branches whose upstream is gone (`[gone]` in `git branch -vv`).

## Testing

- Every function in the code must be unit tested. A change that adds or modifies a function includes its tests.
- Keep logic in plain Kotlin (the `domain` module) and access to Android APIs behind thin interfaces,
  so that everything can be tested on the JVM without a device.
- State machines are tested exhaustively: every (state, event) pair has a test.

## Documentation

- All documentation lives in `docs/`. The only Markdown file outside `docs/` is `README.md`,
  which gives a short overview and links to the documents in `docs/`.
- All diagrams are written in [Mermaid](https://mermaid.js.org/) inside the Markdown files.
  No image files, ASCII art or other diagram tools.
- When code changes behaviour that is documented, update the documentation in the same change.
- New open questions go into `docs/open_questions.md`; answered ones are moved into the relevant document.
