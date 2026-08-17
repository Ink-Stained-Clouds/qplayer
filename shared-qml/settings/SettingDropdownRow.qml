import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// SettingSpec.DROPDOWN — an int index selected from the spec's option labels.
ColumnLayout {
    id: row
    property var spec: null
    spacing: 4

    SettingTitle { text: row.spec ? row.spec.title : "" }
    SettingDesc { text: row.spec ? row.spec.desc : "" }
    ComboBox {
        Layout.fillWidth: true
        Layout.topMargin: 4
        type: "outlined"
        model: row.spec ? row.spec.options : []
        currentIndex: row.spec ? settings.value(row.spec.key) : -1
        onActivated: settings.setValue(row.spec.key, index)
    }
}
