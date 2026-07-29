import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// SettingSpec.TEXT — a string, committed on Enter or via 应用 (never per
// keystroke: these are URLs, JSON paths and directory paths, and every write
// rebuilds the custom-API config / rescans a folder).
ColumnLayout {
    id: row
    property var spec: null
    spacing: 4

    SettingTitle { text: row.spec ? row.spec.title : "" }
    SettingDesc { text: row.spec ? row.spec.desc : "" }
    RowLayout {
        Layout.fillWidth: true
        spacing: 8
        TextField {
            id: field
            Layout.fillWidth: true
            type: "outlined"
            labelBackgroundColor: Theme.color.surfaceContainerHighest
            label: row.spec ? row.spec.hint : ""
            text: row.spec ? settings.value(row.spec.key) : ""
            onAccepted: settings.setValue(row.spec.key, field.text)
        }
        Button {
            type: "tonal"; text: "应用"
            onClicked: settings.setValue(row.spec.key, field.text)
        }
    }
}
