import QtQuick
import QtQuick.Layouts
import md3.Core

// Shared empty state for destinations that cannot work until at least one
// source plugin is active. It covers the destination beneath it so stale data
// and controls from a previously removed source cannot still be operated.
Rectangle {
    color: Theme.color.surface

    MouseArea { anchors.fill: parent }

    ColumnLayout {
        anchors.centerIn: parent
        spacing: 12

        Text {
            Layout.alignment: Qt.AlignHCenter
            text: i18n.t("source.prompt.title")
            color: Theme.color.onSurfaceVariantColor
            fontSize: 15
        }

        Button {
            Layout.alignment: Qt.AlignHCenter
            type: "filledTonal"
            icon: "extension"
            text: i18n.t("source.prompt.button")
            onClicked: player.requestSourceSetup()
        }
    }
}
