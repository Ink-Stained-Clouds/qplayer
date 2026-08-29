import QtQuick 2.15

Item {
    id: button
    property string icon: ""
    property bool emphasized: false
    signal clicked()

    width: 40
    height: 44

    Rectangle {
        anchors.centerIn: parent
        width: 38
        height: 38
        radius: 19
        color: button.emphasized ? desktopLyric.secondaryContainerColor : "transparent"

        Rectangle {
            anchors.fill: parent
            radius: parent.radius
            color: button.emphasized
                   ? desktopLyric.onSecondaryContainerColor
                   : desktopLyric.onSurfaceVariantColor
            opacity: controlMouse.pressed ? 0.16 : (controlMouse.containsMouse ? 0.08 : 0)
        }

        Text {
            anchors.centerIn: parent
            text: button.icon
            font.family: "Material Symbols Rounded"
            font.pixelSize: button.emphasized ? 25 : 23
            font.weight: Font.Normal
            color: button.emphasized
                   ? desktopLyric.onSecondaryContainerColor
                   : desktopLyric.onSurfaceVariantColor
        }

        MouseArea {
            id: controlMouse
            anchors.fill: parent
            hoverEnabled: true
            onClicked: button.clicked()
        }
    }
}
