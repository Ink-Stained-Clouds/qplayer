import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// SettingSpec.ACTION — a control with no stored value: an IconButton when the
// spec names an icon (关于's link / system_update), a labelled Button otherwise
// (选择… / 清除缓存). The handler is registered by the host or by SettingsCore
// and reached by id, so this row never grows a per-action branch.
//
// A `provider` supplies live text — inline beside the title (app version, cache
// usage) or on its own line below it (the current font).
ColumnLayout {
    id: row
    property var spec: null
    property string providerText: (row.spec && row.spec.provider.length > 0)
                                  ? settings.info(row.spec.provider) : ""
    spacing: 4

    RowLayout {
        Layout.fillWidth: true
        spacing: 8
        SettingTitle { text: row.spec ? row.spec.title : "" }
        Text {
            visible: row.spec && row.spec.inlineProvider
            Layout.alignment: Qt.AlignVCenter
            text: row.providerText
            color: Theme.color.primary
            font.family: Theme.typography.labelMedium.family
            font.pixelSize: Theme.typography.labelMedium.size
        }
        IconButton {
            visible: row.spec && row.spec.icon.length > 0
            Layout.alignment: Qt.AlignVCenter
            type: "standard"
            icon: row.spec ? row.spec.icon : ""
            onClicked: settings.invoke(row.spec.action)
        }
        Button {
            visible: row.spec && row.spec.icon.length === 0
            type: row.spec ? row.spec.buttonType : "filledTonal"
            text: row.spec ? row.spec.button : ""
            onClicked: settings.invoke(row.spec.action)
        }
    }
    SettingDesc {
        visible: text.length > 0 && row.spec && !row.spec.inlineProvider
        text: row.providerText
    }
    SettingDesc { text: row.spec ? row.spec.desc : "" }
}
