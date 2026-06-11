import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// 本地: tracks scanned from the device Music folder.
Item {
    id: page

    Flickable {
        anchors.fill: parent
        clip: true
        contentHeight: col.height
        visible: player.libraryCount > 0

        Column {
            id: col
            width: parent.width
            SectionHeader { width: col.width; text: "本地音乐" }
            Repeater {
                model: player.tracks
                SongRow {
                    width: col.width
                    rowTitle: modelData.title
                    rowArtist: modelData.artist
                    highlighted: index === player.index
                    onActivated: player.play(index)
                }
            }
        }
    }

    Text {
        anchors.centerIn: parent
        visible: player.libraryCount === 0
        text: "未找到本地音乐\n把歌曲放进 Music 文件夹"
        horizontalAlignment: Text.AlignHCenter
        color: Theme.color.onSurfaceVariantColor
        fontSize: 15
    }
}
