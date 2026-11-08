"""Screen objects for the journeys, through snippet-uiautomator (D4).

Elements are found by the test tags the app exposes as resource ids, and by
their texts. System dialogs are found by their package, since their texts
follow the phone's language: the S24 runs in German.
"""

import datetime
import time

from mobly import asserts
from snippet_uiautomator import uiautomator

from lib import app

TIMEOUT = datetime.timedelta(seconds=10)
# The companion device manager's dialog, which asks to link a device
COMPANION_PACKAGES = ("com.android.companiondevicemanager", "com.samsung.android.companiondevicemanager")
# Its positive button, by text in the phones' languages
ALLOW = r"(?i)(allow|zulassen|erlauben|ok|verknüpfen|koppeln|pair|link)"
# The permission dialog's buttons, the same ids on every phone with a runtime permission
PERMISSION_ALLOW = "com.android.permissioncontroller:id/permission_allow_button"
# The title of the expanded card's first row of chips, only an expanded card shows it
EXPANDED_MARK = "Codec"
# The preferred codec banner's titles, by the status preferred_status() returns
PREFERRED_TITLES = {"active": "Preferred codec is active", "saved": "Preferred codec", "none": "No preferred codec"}
# The text of a card whose codec can only be read once the device is linked
LINK_PROMPT = "Link this device to see and change its codec."
# Swipes of 80 % of the card's visible height reach either end of the longest expanded card
MAX_SWIPES = 5
SETTLE = datetime.timedelta(seconds=1)
# A card's animation takes 0.4 s
TOGGLE_TIMEOUT = datetime.timedelta(seconds=3)
PERMISSION_DENY = ("com.android.permissioncontroller:id/permission_deny_button",
                   "com.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button")


def register(ad):
    """Starts snippet-uiautomator on the phone, it runs in its own process."""
    if not ad.services.has_service_by_name(uiautomator.ANDROID_SERVICE_NAME):
        ad.services.register(uiautomator.ANDROID_SERVICE_NAME, uiautomator.UiAutomatorService)


class MainScreen:
    """A connected device's card in the Connected devices list.

    The collapsed card shows the codec's chips, and the Preferred Codec chip while the preferred codec is active.
    Expanded, it shows the preferred codec's banner with Restore or Delete, the rows of chips to choose a codec,
    and Save as preferred at its end. The helpers expand or collapse the card as they need it, so callers don't
    track its state.
    """

    def __init__(self, ad, headset):
        self.ad = ad
        self.ui = ad.ui
        self.headset = headset
        self.card_res = "device_" + headset.address.upper()

    def open(self):
        app.launch(self.ad.adb)
        return self.wait_for_card()

    def wait_for_card(self):
        asserts.assert_true(self.card().wait.exists(TIMEOUT), "the device's card isn't shown")
        return self

    def card(self):
        return self.ui(res=self.card_res)

    def is_expanded(self):
        return self.card().child(text=EXPANDED_MARK).exists

    def scroll_to_top(self):
        """Scrolls the list back until the card's name shows, a long expanded card pushes it out of view."""
        self.swipe_until(self.card().child(text=self.headset.name), up=False)

    def scroll_to(self, **selector):
        """Scrolls the list down from the card's top until the card shows an element matching [selector], whether it
        does. Slow when there's none, so only for elements that should be there."""
        self.scroll_to_top()
        return self.swipe_until(self.card().child(**selector), up=True)

    def in_view(self, element):
        """Whether [element] shows clear of the bars, so a tap on it hits it.

        The top bar clips the list: an element it cuts has the top of the card's visible part, while the card's
        first element is 10 dp below it otherwise. The navigation bar overlays the list without clipping it, so a
        tap's point, the element's center, has to be above the tallest one, 48 dp.
        """
        if not element.exists:
            return False
        bounds = element.info["visibleBounds"]
        card = self.card().info["visibleBounds"]
        info = self.ui.info
        navigation_bar = 48 * info["displayHeight"] / info["displaySizeDpY"]
        return bounds["top"] > card["top"] and (bounds["top"] + bounds["bottom"]) / 2 < info["displayHeight"] - navigation_bar

    def swipe_until(self, element, up):
        """Swipes the card [up], scrolling the list down, or down until [element] shows, see in_view(); whether it
        does."""
        for _ in range(MAX_SWIPES):
            if self.in_view(element):
                return True
            if up:
                self.card().swipe.up(percent=80)
            else:
                self.card().swipe.down(percent=80)
            # Once the list settled, about 0.5 s later. Without the refresh UiAutomator can keep what it saw before
            # the swipe, the elements that were out of view as invisible and the others where they were.
            time.sleep(SETTLE.total_seconds())
            self.refresh()
        return self.in_view(element)

    def refresh(self):
        """Makes UiAutomator read the screen anew. After a scroll it can go on taking the elements that were out of
        view for invisible and tapping the others where they were, seen on API 32: it keeps them in its cache. A
        window coming and going clears the cache, here the app menu."""
        asserts.assert_true(self.ui(res="app_menu").wait.click(TIMEOUT), "no app menu")
        self.ui(res="open_log").wait.exists(TIMEOUT)
        self.ad.adb.shell("input keyevent KEYCODE_BACK")
        self.ui(res="open_log").wait.gone(TIMEOUT)

    def toggle(self):
        """Expands or collapses the card, by tapping the device's name."""
        self.scroll_to_top()
        asserts.assert_true(self.card().child(text=self.headset.name).wait.click(TIMEOUT),
                            "can't tap the device's name")

    def expand(self):
        self.set_expanded(True)

    def collapse(self):
        self.set_expanded(False)

    def set_expanded(self, expanded):
        """Expands or collapses the card unless it already is. Once more after a refresh if the card doesn't change:
        when the list's length changes, UiAutomator can keep the name where it was, see refresh()."""
        mark = self.card().child(text=EXPANDED_MARK)
        for _ in range(2):
            if mark.exists == expanded:
                return
            self.toggle()
            if mark.wait.exists(TOGGLE_TIMEOUT) if expanded else mark.wait.gone(TOGGLE_TIMEOUT):
                return
            self.refresh()
        asserts.assert_equal(expanded, mark.exists, "whether the card is expanded")

    def chip(self, text):
        return self.card().child(text=text)

    def tap_chip(self, text):
        """Taps a chip of the expanded card, to choose a codec or one of its values."""
        self.expand()
        asserts.assert_true(self.scroll_to(text=text), f"no chip {text!r}")
        asserts.assert_true(self.chip(text).wait.click(TIMEOUT), f"no chip {text!r}")

    def shows(self, *texts):
        """Whether the collapsed card shows all [texts], its chips."""
        self.collapse()
        return all(self.chip(text).wait.exists(TIMEOUT) for text in texts)

    def preferred_chip_shown(self, shown=True):
        """Waits until the collapsed card shows the Preferred Codec chip, or doesn't if not [shown]; whether it did."""
        self.collapse()
        chip = self.card().child(res="preferred_chip")
        return chip.wait.exists(TIMEOUT) if shown else chip.wait.gone(TIMEOUT)

    def banner(self):
        return self.card().child(res="preferred_banner")

    def preferred_status(self):
        """The expanded card's preferred codec banner: "active" with Delete, "saved" with Restore while the
        preferred codec isn't active, "none" while nothing is saved; None without a banner, for an unknown codec."""
        self.expand()
        self.scroll_to_top()
        if not self.banner().wait.exists(TIMEOUT):
            return None
        return next((status for status, title in PREFERRED_TITLES.items() if self.banner().child(text=title).exists),
                    "unknown")

    def wait_preferred(self, status):
        """Waits until the banner shows [status], see preferred_status(); whether it does."""
        self.expand()
        self.scroll_to_top()
        return self.banner().child(text=PREFERRED_TITLES[status]).wait.exists(TIMEOUT)

    def tap_save(self):
        """Taps Save as preferred, at the end of the expanded card."""
        self.expand()
        asserts.assert_true(self.scroll_to(res="save_preferred"), "no Save as preferred button")
        asserts.assert_true(self.card().child(res="save_preferred").wait.click(TIMEOUT), "no Save as preferred button")

    def tap_restore(self):
        self.tap_banner_button("restore_preferred")

    def tap_delete(self):
        self.tap_banner_button("clear_preferred")

    def tap_banner_button(self, tag):
        self.expand()
        self.scroll_to_top()
        asserts.assert_true(self.banner().child(res=tag).wait.click(TIMEOUT), f"the banner has no button {tag}")

    def shows_link_prompt(self):
        """Whether the card asks to link the device, the card of a device whose codec is unreadable without it."""
        return self.card().child(text=LINK_PROMPT).wait.exists(TIMEOUT)

    def tap_link(self):
        asserts.assert_true(self.card().child(res="link_device").wait.click(TIMEOUT), "no Link Device button")

    def open_log(self):
        asserts.assert_true(self.ui(res="app_menu").wait.click(TIMEOUT), "no app menu")
        asserts.assert_true(self.ui(res="open_log").wait.click(TIMEOUT), "no Log item")
        return LogScreen(self.ad).wait()

    def answer_permission(self, allow):
        """Answers the system's permission dialog."""
        if allow:
            asserts.assert_true(self.ui(res=PERMISSION_ALLOW).wait.click(TIMEOUT), "no permission dialog")
            return
        asserts.assert_true(self.ui(res=PERMISSION_ALLOW).wait.exists(TIMEOUT), "no permission dialog")
        button = next((res for res in PERMISSION_DENY if self.ui(res=res).exists), None)
        asserts.assert_is_not_none(button, "the permission dialog has no button to deny")
        self.ui(res=button).click()
        self.ui(res=PERMISSION_ALLOW).wait.gone(TIMEOUT)

    def shows_text(self, text):
        return self.ui(text=text).wait.exists(TIMEOUT)

    def tap_text(self, text):
        asserts.assert_true(self.ui(text=text).wait.click(TIMEOUT), f"nothing to tap with {text!r}")

    def accept_link_dialog(self):
        """Accepts the companion device manager's dialog that links the device."""
        dialog = None
        for package in COMPANION_PACKAGES:
            if self.ui(pkg=package).wait.exists(TIMEOUT):
                dialog = package
                break
        asserts.assert_is_not_none(dialog, "the dialog to link the device didn't appear")
        # Some versions list the device to choose first
        name = self.ui(pkg=dialog, textContains=self.headset.name)
        allow = self.ui(pkg=dialog, textMatches=ALLOW, enabled=True)
        if not allow.wait.exists(datetime.timedelta(seconds=3)) and name.exists:
            name.click()
        asserts.assert_true(allow.wait.click(TIMEOUT), "the dialog has no button to allow linking")
        self.ui(pkg=dialog).wait.gone(TIMEOUT)


class LogScreen:
    def __init__(self, ad):
        self.ad = ad
        self.ui = ad.ui

    def wait(self):
        asserts.assert_true(self.ui(res="share_log").wait.exists(TIMEOUT), "the log screen isn't shown")
        return self

    def entry(self, seq):
        """The entry whose first event has [seq], the trigger of an operation."""
        return self.ui(res=f"log_entry_{seq}")

    def entry_shows(self, seq, pattern):
        """Whether the entry shows a text matching the regular expression [pattern]."""
        return self.entry(seq).child(textMatches=pattern).wait.exists(TIMEOUT)

    def toggle(self, seq):
        asserts.assert_true(self.entry(seq).wait.click(TIMEOUT), f"no log entry {seq}")

    def share(self, full=False):
        """Shares the log from its dialog and closes the system's share sheet."""
        asserts.assert_true(self.ui(res="share_log").wait.click(TIMEOUT), "no share button")
        if full:
            asserts.assert_true(self.ui(text="Full").wait.click(TIMEOUT), "no Full option")
        asserts.assert_true(self.ui(text="Share").wait.click(TIMEOUT), "no Share button")
        self.ui(text="Share").wait.gone(TIMEOUT)
        # The share sheet
        self.ad.adb.shell("sleep 2; input keyevent KEYCODE_BACK")
