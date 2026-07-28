import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// SettingSpec.STEPPER — an int with −/+ buttons. Clamping lives in SettingsCore
// (bump()), so the range is never repeated here; `scale` divides the stored value
// for display only (line spacing persists as 200 and shows as 2.00×).
ColumnLayout {
    id: row
    property var spec: null
    property int value: row.spec ? settings.value(row.spec.key) : 0
    property string display: {
        if (!row.spec) return ""
        var v = row.spec.scale > 1 ? (row.value / row.spec.scale).toFixed(2) : ("" + row.value)
        return v + row.spec.unit
    }
    spacing: 4

    RowLayout {
        Layout.fillWidth: true
        spacing: 8
        SettingTitle { text: row.spec ? row.spec.title : "" }
        Button {
            type: "outlined"; text: "−"
            onClicked: settings.bump(row.spec.key, -1)
        }
        Text {
            text: row.display
            color: Theme.color.onSurfaceColor
            font.family: Theme.typography.bodyLarge.family
            font.pixelSize: Theme.typography.bodyLarge.size
        }
        Button {
            type: "outlined"; text: "+"
            onClicked: settings.bump(row.spec.key, 1)
        }
    }
    SettingDesc { text: row.spec ? row.spec.desc : "" }
}
