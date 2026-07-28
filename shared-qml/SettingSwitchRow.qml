import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// SettingSpec.SWITCH. `spec` is the descriptor handed down by SettingsPage's
// Loader; the value is read/written by key, so this one file serves every toggle
// in the catalog. An "swatch" accessory draws the live Monet seed beside the
// title, as the hand-written 莫奈取色 card did.
ColumnLayout {
    id: row
    property var spec: null
    spacing: 4

    RowLayout {
        Layout.fillWidth: true
        spacing: 12
        Rectangle {
            visible: row.spec && row.spec.accessory === "swatch"
            Layout.preferredWidth: 40
            Layout.preferredHeight: 40
            radius: 12
            color: Theme.color.primary
        }
        SettingTitle { text: row.spec ? row.spec.title : "" }
        Switch {
            checked: row.spec ? settings.value(row.spec.key) === true : false
            onClicked: settings.setValue(row.spec.key, checked)
        }
    }
    SettingDesc { text: row.spec ? row.spec.desc : "" }
}
