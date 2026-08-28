import QtQuick 2.15

Item {
    id: root
    width: 760
    height: 118

    readonly property color foreground: "#FFFFFFFF"
    readonly property color secondary: "#B8FFFFFF"
    readonly property int lyricWeight: desktopLyric.fontWeight === 0 ? Font.Thin
                                       : desktopLyric.fontWeight === 1 ? Font.Light
                                       : desktopLyric.fontWeight === 3 ? Font.Medium
                                       : Font.Normal

    Rectangle {
        id: surface
        anchors.fill: parent
        anchors.margins: 5
        radius: 28
        color: desktopLyric.dark ? "#CC101114" : "#D9F7F7FA"

        Text {
            id: currentShadow
            x: current.x + 1
            y: current.y + 2
            width: current.width
            height: current.height
            text: current.text
            visible: desktopLyric.shadow
            color: "#99000000"
            font.pixelSize: current.font.pixelSize
            font.weight: current.font.weight
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight
        }

        Text {
            id: current
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.leftMargin: 32
            anchors.rightMargin: 32
            y: desktopLyric.translationText === "" ? 18 : 9
            height: desktopLyric.translationText === "" ? 66 : 55
            text: desktopLyric.currentText
            color: desktopLyric.dark ? root.foreground : "#FF1C1C1E"
            font.pixelSize: desktopLyric.fontSize
            font.weight: root.lyricWeight
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight
        }

        Text {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.leftMargin: 40
            anchors.rightMargin: 40
            y: 65
            height: 28
            visible: desktopLyric.translationText !== ""
            text: desktopLyric.translationText
            color: desktopLyric.dark ? root.secondary : "#A63C3C43"
            font.pixelSize: Math.max(13, desktopLyric.fontSize * 0.52)
            font.weight: Font.Normal
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight
        }

        Text {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.leftMargin: 40
            anchors.rightMargin: 40
            y: 82
            height: 22
            visible: desktopLyric.translationText === "" && desktopLyric.nextText !== ""
            text: desktopLyric.nextText
            color: desktopLyric.dark ? "#70FFFFFF" : "#7048484A"
            font.pixelSize: Math.max(12, desktopLyric.fontSize * 0.45)
            font.weight: Font.Normal
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight
        }
    }
}
