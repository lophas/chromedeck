# ChromeDeck keyboard extension

Opens Android's on-screen keyboard when a text field is tapped in Chrome. The content
script reports taps on editable fields and focus leaving them; the service worker
passes them to the native messaging host `com.chromedeck.keyboard`
(`lorie-app/src/main/assets/chromedeck-keyboard/host.py`), which writes
`/tmp/desktop-chrome-keyboard` for the app.

The app installs the packed extension, `lorie-app/src/main/assets/chromedeck-keyboard/chromedeck-keyboard.crx`,
as an external extension (ID `mlfpakdjihkojooofakdipafhmmnndeh`), which Linux Chrome
enables without a prompt.

After changing this directory, raise `version` in `manifest.json` and in
`RuntimeInstaller.KEYBOARD_EXTENSION_VERSION`, then pack it with any Chrome and the
extension key, kept outside the repository with the release signing key:

    chrome --pack-extension=extensions/chromedeck-keyboard \
        --pack-extension-key=~/.config/desktop-chrome-android/chromedeck-keyboard.pem

and copy the resulting `chromedeck-keyboard.crx` into the assets.
