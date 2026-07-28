import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// SettingSpec.RADIO — an int index shown as a radio group (背景动效's 动态/静态).
ColumnLayout {
    id: row
    property var spec: null
    spacing: 4

    SettingTitle { text: row.spec ? row.spec.title : "" }
    SettingDesc { text: row.spec ? row.spec.desc : "" }
    RowLayout {
        Layout.fillWidth: true
        Layout.topMargin: 4
        spacing: 16
        Repeater {
            model: row.spec ? row.spec.options : []
            RadioButton {
                text: modelData
                checked: settings.value(row.spec.key) === index
                onClicked: settings.setValue(row.spec.key, index)
            }
        }
        Item { Layout.fillWidth: true }
    }
}
