import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// 最近: netease listen history (signed in).
Item {
    id: page
    signal requestLogin()

    Flickable {
        anchors.fill: parent
        clip: true
        contentHeight: col.height
        visible: player.loggedIn

        Column {
            id: col
            width: parent.width
            SectionHeader { width: col.width; text: "最近播放" }
            Repeater {
                model: player.recentSongs
                SongRow {
                    width: col.width
                    rowTitle: modelData.name
                    rowArtist: modelData.artist
                    onActivated: player.playRecentSong(index)
                }
            }
        }
    }

    ColumnLayout {
        anchors.centerIn: parent
        spacing: 12
        visible: !player.loggedIn
        Text {
            Layout.alignment: Qt.AlignHCenter
            text: "登录后查看最近播放"
            color: Theme.color.onSurfaceVariantColor
            fontSize: 15
        }
        Button {
            Layout.alignment: Qt.AlignHCenter
            type: "filled"; text: "扫码登录"
            onClicked: page.requestLogin()
        }
    }
}
