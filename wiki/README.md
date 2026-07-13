# StarChem Wiki Source

This directory contains the complete GitHub Wiki source for **StarChem v1.1.0-alpha**.

GitHub stores a repository wiki in a separate Git repository named `StarChem.wiki.git`. The connected GitHub integration could read and write the main StarChem repository, but GitHub returned `404 Not Found` when the integration attempted to access or initialize that separate wiki repository. The pages are therefore committed here in import-ready form.

## Importing into the GitHub Wiki

1. Enable the repository Wiki in **Settings → Features → Wikis** if it is disabled.
2. Create the first Wiki page in GitHub’s web interface. This initializes `StarChem.wiki.git`.
3. Clone the initialized wiki repository:

   ```bash
   git clone https://github.com/tndmadman/StarChem.wiki.git
   ```

4. Copy the contents of this `wiki/` directory into the root of the cloned wiki repository. Do not copy this `README.md` unless you want it to become a visible Wiki page.
5. Commit and push:

   ```bash
   git add .
   git commit -m "Publish StarChem v1.1.0-alpha wiki"
   git push
   ```

GitHub Wiki page links such as `[[Getting Started]]` resolve from filenames such as `Getting-Started.md`. `_Sidebar.md` supplies navigation and `_Footer.md` supplies the release footer.

## Release scope

The pages are grounded in tag `v1.1.0-alpha`, commit `81075c4618bed69dca7a0e53f6d8ca4628683384`. Features added later to `main` are excluded until they ship in a tagged release.