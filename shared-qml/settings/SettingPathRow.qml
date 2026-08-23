import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// SettingSpec.PATH — a host-picked directory. Paths are intentionally read-only:
// desktop opens the platform chooser and Android does not expose these rows.
ColumnLayout {
    id: row
    property var spec: null
    property string selectedPath: row.spec ? String(settings.value(row.spec.key) || "") : ""
    spacing: 6

    RowLayout {
        Layout.fillWidth: true
        spacing: 8
        SettingTitle { text: row.spec ? row.spec.title : "" }
        Button {
            type: "filledTonal"
            text: "选择目录"
            onClicked: settings.pickDirectory(row.spec.key)
        }
    }

    SettingDesc { text: row.spec ? row.spec.desc : "" }

    Rectangle {
        Layout.fillWidth: true
        Layout.preferredHeight: Math.max(48, pathText.implicitHeight + 20)
        radius: 8
        color: "transparent"
        border.width: 1
        border.color: Theme.color.outline

        Text {
            id: pathText
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            anchors.leftMargin: 12
            anchors.rightMargin: 12
            text: row.selectedPath.length > 0
                  ? row.selectedPath
                  : (row.spec && row.spec.hint.length > 0 ? row.spec.hint : "未选择目录")
            color: row.selectedPath.length > 0
                   ? Theme.color.onSurfaceColor : Theme.color.onSurfaceVariantColor
            font.family: Theme.typography.bodyMedium.family
            font.pixelSize: Theme.typography.bodyMedium.size
            wrapMode: Text.WrapAnywhere
        }
    }
}
