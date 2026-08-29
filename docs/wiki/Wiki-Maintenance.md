# Wiki Maintenance

The canonical Markdown source is stored under `docs/wiki/` in the main repository.

## Why keep source in the repository?

GitHub Wikis use a separate Git repository. Keeping the source under normal version control means documentation changes can be reviewed and preserved with the application even if the Wiki mirror is rebuilt.

## Mirroring to the GitHub Wiki

A mirror process should copy all `docs/wiki/*.md` pages into the repository Wiki Git repository while preserving filenames such as `Home.md`, `_Sidebar.md` and `_Footer.md`.

Do not edit generated/mirrored pages in a way that cannot be represented back in the source directory, otherwise the next sync may overwrite the changes.

## Adding a page

1. Add `docs/wiki/Page-Name.md`.
2. Link it from `Home.md` and `_Sidebar.md` when it belongs in navigation.
3. Review relative links.
4. Sync/mirror the wiki.

## Renaming a page

Update incoming links before removing the old name. GitHub Wiki page URLs are derived from filenames/titles, so renames can break external links.
