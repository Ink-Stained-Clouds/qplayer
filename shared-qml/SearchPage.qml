import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// 搜索页：空输入显示热门搜索，输入时实时搜索，结果可点击播放。
Item {
    id: page

    Component.onCompleted: player.loadHotSearches()

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.margins: 12
            spacing: 4

            TextField {
                id: query
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                type: "filled"
                leadingIcon: "search"
                label: "搜索网易云歌曲"
                // Real-time search on every keystroke
                onTextChanged: {
                    if (text.length > 0) player.search(text)
                }
                onAccepted: player.search(query.text)
            }
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "filled"; icon: "search"
                onClicked: player.search(query.text)
            }
        }

        // --- Hot searches (shown when input is empty) ---
        // Explicit index-positioned rows in a plain Item, NOT a Column positioner:
        // qml4j lays Repeater delegates out by their own x/y, it does not flow
        // dynamically-created children through a positioner (same idiom as HomePage /
        // VirtualSongList). A Column here left the rows unpositioned/zero-width — the
        // "hot search shows nothing" bug.
        Item {
            id: hotArea
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: query.text.length === 0

            property int rowH: 44
            property int hotCount: player.hotSearches ? player.hotSearches.length : 0

            Flickable {
                anchors.fill: parent
                clip: true
                contentWidth: width
                contentHeight: 56 + hotArea.hotCount * hotArea.rowH + 16

                Text {
                    x: 16; y: 16
                    text: "热门搜索"
                    font.pixelSize: 18
                    font.weight: Font.DemiBold
                    color: Theme.color.onSurfaceColor
                }

                Item {
                    x: 0; y: 56
                    width: hotArea.width
                    height: hotArea.hotCount * hotArea.rowH

                    Repeater {
                        model: player.hotSearches

                        Item {
                            width: hotArea.width
                            height: hotArea.rowH
                            y: index * hotArea.rowH

                            Rectangle {
                                anchors.fill: parent
                                anchors.leftMargin: 12
                                anchors.rightMargin: 12
                                radius: 8
                                color: hma.pressed ? Theme.color.surfaceContainerHigh : "transparent"
                            }

                            Row {
                                anchors.left: parent.left
                                anchors.leftMargin: 24
                                anchors.verticalCenter: parent.verticalCenter
                                spacing: 8

                                Text {
                                    text: "search"
                                    font.family: Theme.iconFont.name
                                    font.pixelSize: 20
                                    color: Theme.color.onSurfaceVariantColor
                                    anchors.verticalCenter: parent.verticalCenter
                                }
                                Text {
                                    text: modelData || ""
                                    font.pixelSize: 15
                                    color: Theme.color.onSurfaceColor
                                    anchors.verticalCenter: parent.verticalCenter
                                }
                            }

                            MouseArea {
                                id: hma
                                anchors.fill: parent
                                onClicked: {
                                    query.text = modelData
                                    player.search(modelData)
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- Search results (shown when input is not empty) ---
        VirtualSongList {
            id: results
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: query.text.length > 0
            list: player.searchResults
            onActivated: player.playSearchResult(results.activatedIndex)
        }
    }
}
