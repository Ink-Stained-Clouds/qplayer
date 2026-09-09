import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// SettingSpec.SEGMENTED — an int index over the spec's own labels.
ColumnLayout {
    id: row
    property var spec: null
    property var buttons: {
        if (!row.spec) return []
        var current = settings.value(row.spec.key)
        var out = []
        for (var i = 0; i < row.spec.options.length; i++) {
            out.push({ text: i18n.t(row.spec.options[i]), selected: i === current })
        }
        return out
    }
    spacing: 4

    SettingTitle { text: row.spec ? i18n.t(row.spec.title) : "" }
    SettingDesc { text: row.spec ? i18n.t(row.spec.desc) : "" }
    SegmentedButton {
        Layout.fillWidth: true
        Layout.topMargin: 4
        buttons: row.buttons
        onClicked: settings.setValue(row.spec.key, index)
    }
}
