import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// App settings overlay. Nothing here knows what a setting IS: the categories and
// the rows come from player-core's SettingsCatalog through the `settings` context
// global (SettingsCore), and each row is rendered by whichever Setting*Row
// component matches its declared type. Adding a setting is a catalog entry —
// no edit here, and none in either platform's host code.
//
// This also keeps the page well clear of the 64KB-per-QML-file constructor limit
// that forced the old hand-written version to be split across six files: the
// markup is now one Repeater plus one Component per row type.
Rectangle {
    id: page
    signal back()
    color: Theme.color.surface

    property var categories: settings.categories()
    property var categoryTabModel: {
        var out = []
        for (var i = 0; i < page.categories.length; i++) out.push({ text: page.categories[i] })
        return out
    }

    // Category switching uses Main.qml's MD3 fade-through verbatim (fade out,
    // swap, fade back in while rising), so it reads the same as switching pages.
    property string currentCategory: page.categories.length > 0 ? page.categories[0] : ""
    property string nextCategory: page.currentCategory
    property real panelOpacity: 1
    property real panelShift: 0
    property var groups: settings.groups(page.currentCategory)

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
    // plain property so this page has a change handler to hang the transition off.
    property int tabIndex: categoryTabs.currentIndex
    onTabIndexChanged: page.selectCategory(page.categories[page.tabIndex])

    // Catch-all so taps on empty areas don't fall through to the page beneath.
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
            // Breathing room under the tab bar so the first card doesn't sit on
            // the indicator.
            Layout.topMargin: 16
            clip: true
            contentWidth: width
            contentHeight: groupsCol.implicitHeight + 24

            ColumnLayout {
                id: groupsCol
                width: settingsFlickable.width
                y: page.panelShift
                opacity: page.panelOpacity
                spacing: 14

                // One card per group, one row per spec inside it — the grouping
                // is declared in the catalog, so the page never names a setting.
                Repeater {
                    model: page.groups

                    delegate: SettingCard {
                        Layout.fillWidth: true
                        Layout.leftMargin: 12
                        Layout.rightMargin: 12

                        property var groupData: modelData

                        Repeater {
                            model: groupData.rows

                            delegate: Loader {
                                Layout.fillWidth: true

                                // A row gated on another setting (the custom-API
                                // block hangs off its own switch) collapses when
                                // that's off, as the old card's block did.
                                visible: modelData.dependsOn.length === 0
                                         || settings.value(modelData.dependsOn) === true

                                // Read inside the loaded component, the same way
                                // MD3 Menu's delegates reach their item data.
                                property var rowSpec: modelData

                                sourceComponent: modelData.type === "switch" ? switchRow
                                               : modelData.type === "stepper" ? stepperRow
                                               : modelData.type === "segmented" ? segmentedRow
                                               : modelData.type === "radio" ? radioRow
                                               : modelData.type === "text" ? textRow
                                               : actionRow
                            }
                        }
                    }
                }
            }

            // Components are Items and are NOT visible:false, so they'd take a
            // slot (plus spacing) inside a layout — keep them under a plain Item.
            Item {
                Component { id: switchRow; SettingSwitchRow { spec: rowSpec } }
                Component { id: stepperRow; SettingStepperRow { spec: rowSpec } }
                Component { id: segmentedRow; SettingSegmentedRow { spec: rowSpec } }
                Component { id: radioRow; SettingRadioRow { spec: rowSpec } }
                Component { id: textRow; SettingTextRow { spec: rowSpec } }
                Component { id: actionRow; SettingActionRow { spec: rowSpec } }
            }
        }
    }

    FontPickerDialog {
        active: settings.fontPickerOpen
        onClosed: settings.fontPickerOpen = false
    }
}
