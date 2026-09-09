import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// SettingSpec.DROPDOWN — an int index selected from the spec's option labels.
ColumnLayout {
    id: row
    property var spec: null
    property var optionLabels: {
        var out = []
        if (!row.spec) return out
        for (var i = 0; i < row.spec.options.length; i++)
            out.push(i18n.t(row.spec.options[i]))
        return out
    }
    spacing: 4

    SettingTitle { text: row.spec ? i18n.t(row.spec.title) : "" }
    SettingDesc { text: row.spec ? i18n.t(row.spec.desc) : "" }
    ComboBox {
        Layout.fillWidth: true
        Layout.topMargin: 4
        type: "outlined"
        model: row.optionLabels
        currentIndex: row.spec ? settings.value(row.spec.key) : -1
        onActivated: settings.setValue(row.spec.key, index)
    }
}
