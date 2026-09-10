import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

SettingCard {
    id: card

    required property var pluginData

    RowLayout {
        Layout.fillWidth: true
        spacing: 12

        Text {
            text: "extension"
            font.family: Theme.iconFont.name
            font.pixelSize: 26
            color: card.pluginData.enabled
                   ? Theme.color.primary
                   : Theme.color.onSurfaceVariantColor
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2

            SettingTitle {
                text: card.pluginData.name
            }
            SettingDesc {
                text: card.pluginData.version + " · "
                      + i18n.t(card.pluginData.primary ? "plugin.entry.primary"
                               : (card.pluginData.enabled ? "plugin.entry.enabled"
                                  : "plugin.entry.disabled"))
            }
        }

        Button {
            type: "outlined"
            icon: "settings"
            text: i18n.t("plugin.entry.settings")
            onClicked: player.requestPluginSettings(card.pluginData.id)
        }
    }

    SettingDesc {
        text: i18n.t("plugin.entry.permissions",
                     card.pluginData.permissions.length > 0
                     ? card.pluginData.permissions
                     : i18n.t("plugin.entry.noPermissions"))
    }
}
