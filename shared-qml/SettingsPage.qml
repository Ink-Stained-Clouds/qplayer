import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// App settings overlay: appearance (dark-mode policy + Monet dynamic color) and
// an about section. Writes the `settings` context global, which drives
// StyleManager through the Bindings in Main.qml. Section containers are plain
// rounded rectangles sized to their content (md3 Card is fixed-size).
//
// Each category's actual card content lives in its own top-level component
// file (AppearanceSettingsCards.qml, PlaybackSettingsCards.qml + the older
// CustomApiSettingsCard.qml, LyricSettingsCards.qml, LocalSettingsCards.qml,
// AboutSettingsCards.qml) rather than inline here — qml4j compiles each QML
// file's root to one JVM constructor, and all 5 panels' markup inline in this
// one file pushed the generated method past the JVM's 64KB bytecode limit
// (MethodTooLargeException at runtime, not caught by `mvn package`).
Rectangle {
    id: page
    signal back()
    color: Theme.color.surface
    property bool fontPickerOpen: false

    // Category tab bar (issue: settings had grown into one long scroll with just
    // inline section labels — this replaces that with an explicit selector).
    // Fixed 5-category list, always the same on every platform: 存储's cache
    // controls are cross-platform (both Settings twins have maxCacheSizeMB) and
    // now live under 本地 alongside the music-folder picker (that one card still
    // individually typeof-guards itself for Android, same as before) — merging
    // them meant one fewer tab than the original 6-category cut.
    property string currentCategory: "外观"
    property var categories: ["外观", "播放", "歌词", "本地", "关于"]
    // md3 Tabs takes {icon, text} entries; these are label-only.
    property var categoryTabModel: [
        { text: "外观" }, { text: "播放" }, { text: "歌词" }, { text: "本地" }, { text: "关于" }
    ]

    // Category switching uses Main.qml's MD3 fade-through verbatim (fade the
    // content out, swap, fade it back in while it rises) instead of the
    // per-panel horizontal slide this page used to have on its own, so moving
    // between settings categories reads the same as moving between the app's
    // main pages. Only one panel is ever visible, so the panels no longer need
    // opaque backings to occlude each other mid-transition either.
    property string nextCategory: "外观"
    property real panelOpacity: 1
    property real panelShift: 0

    function selectCategory(name) {
        if (!name || name === page.currentCategory) return
        page.nextCategory = name
        categoryAnim.restart()
    }

    SequentialAnimation {
        id: categoryAnim
        NumberAnimation {
            target: page; property: "panelOpacity"; to: 0
            duration: 90; easing.type: Easing.OutCubic
        }
        ScriptAction {
            onTrigger: {
                page.currentCategory = page.nextCategory
                // Each category scrolls independently, so the incoming one starts
                // at its top rather than inheriting the previous panel's offset
                // (which could be past the end of a shorter panel).
                settingsFlickable.contentY = 0
                page.panelShift = 28
            }
        }
        ParallelAnimation {
            NumberAnimation {
                target: page; property: "panelOpacity"; from: 0; to: 1
                duration: 220; easing.type: Easing.OutCubic
            }
            NumberAnimation {
                target: page; property: "panelShift"; from: 28; to: 0
                duration: 220; easing.type: Easing.OutCubic
            }
        }
    }

    // Tabs owns currentIndex (its Ripple writes it directly); mirror it into a
    // plain property so this page has a change handler of its own to hang the
    // transition off, the same way Main.qml watches player.toast.
    property int tabIndex: categoryTabs.currentIndex
    onTabIndexChanged: page.selectCategory(page.categories[page.tabIndex])

    // Catch-all so taps on empty areas don't fall through to the page beneath.
    // Declared first (lowest z); the controls above still receive their events.
    MouseArea { anchors.fill: parent }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 64
            Layout.leftMargin: 4
            Layout.rightMargin: 16
            spacing: 4
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"; icon: "arrow_back"
                onClicked: page.back()
            }
            Text {
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                text: "设置"
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleLarge.family
                font.pixelSize: Theme.typography.titleLarge.size
            }
        }

        // Category selector: md3's own Tabs bar (secondary type — full-slot
        // 2dp indicator, no icons), replacing the hand-rolled Repeater +
        // arithmetic underline this page carried. Sized to the bar itself: the
        // panels below scroll as one Flickable rather than living in Tabs' own
        // StackLayout content area, since they differ in height and share the
        // page's scroll position.
        Tabs {
            id: categoryTabs
            Layout.fillWidth: true
            Layout.preferredHeight: 48
            Layout.leftMargin: 12
            Layout.rightMargin: 12
            type: "secondary"
            model: page.categoryTabModel
        }

        Flickable {
            id: settingsFlickable
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            contentWidth: width
            // Bound to whichever panel is showing — the others are invisible and
            // occupy no scroll range.
            contentHeight: {
                switch (page.currentCategory) {
                case "外观": return panelAppearance.height + 24
                case "播放": return panelPlayback.height + 24
                case "歌词": return panelLyric.height + 24
                case "本地": return panelLocal.height + 24
                case "关于": return panelAbout.height + 24
                default: return 0
                }
            }

            // Plain Item, not a Layout: the 5 category panels are stacked at the
            // same origin and toggled by `visible`. height must be bound
            // explicitly (plain Item doesn't default height to implicitHeight
            // the way real Qt Quick does) — qml4j's hit-testing walks every
            // ancestor's own width/height as a bounding-box check on the way
            // down to a MouseArea/Button, so a 0-height Item here silently
            // swallows every click to everything beneath it. y/opacity carry the
            // fade-through for whichever panel is showing, exactly as pageBody
            // does for the main pages in Main.qml.
            Item {
                id: content
                width: parent.width
                height: parent.contentHeight
                y: page.panelShift
                opacity: page.panelOpacity

                Item {
                    id: panelAppearance
                    width: parent.width
                    height: appearanceCards.implicitHeight
                    visible: page.currentCategory === "外观"

                    AppearanceSettingsCards {
                        id: appearanceCards
                        width: parent.width
                        onPickFont: page.fontPickerOpen = true
                    }
                } // end 外观

                Item {
                    id: panelPlayback
                    width: parent.width
                    height: playbackCards.implicitHeight
                    visible: page.currentCategory === "播放"

                    ColumnLayout {
                        id: playbackCards
                        width: parent.width
                        spacing: 14
                        PlaybackSettingsCards { Layout.fillWidth: true }
                        // See CustomApiSettingsCard.qml — factored into its own file
                        // for the same 64KB-method reason noted above.
                        CustomApiSettingsCard {}
                    }
                } // end 播放

                Item {
                    id: panelLyric
                    width: parent.width
                    height: lyricCards.implicitHeight
                    visible: page.currentCategory === "歌词"

                    LyricSettingsCards {
                        id: lyricCards
                        width: parent.width
                    }
                } // end 歌词

                Item {
                    id: panelLocal
                    width: parent.width
                    height: localCards.implicitHeight
                    visible: page.currentCategory === "本地"

                    LocalSettingsCards {
                        id: localCards
                        width: parent.width
                    }
                } // end 本地

                Item {
                    id: panelAbout
                    width: parent.width
                    height: aboutCards.implicitHeight
                    visible: page.currentCategory === "关于"

                    AboutSettingsCards {
                        id: aboutCards
                        width: parent.width
                    }
                } // end 关于
            }
        }
    }

    FontPickerDialog {
        active: page.fontPickerOpen
        onClosed: page.fontPickerOpen = false
    }
}
