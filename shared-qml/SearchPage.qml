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
                onAccepted: { player.search(query.text); player.addSearchHistory(query.text) }
            }
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "filled"; icon: "search"
                onClicked: { player.search(query.text); player.addSearchHistory(query.text) }
            }
        }

        // --- History + Hot searches (shown when input is empty) ---
        Item {
            id: hotArea
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: query.text.length === 0

            property int rowH: 44
            property int hotCount: player.hotSearches ? player.hotSearches.length : 0
            property int histCount: player.searchHistory ? player.searchHistory.length : 0
            // y-offset where hot searches begin (below history section if any)
            property int hotY: histCount > 0 ? (56 + histCount * rowH + 16) : 0

            Flickable {
                anchors.fill: parent
                clip: true
                contentWidth: width
                contentHeight: hotArea.hotY + 56 + hotArea.hotCount * hotArea.rowH + 16

                // ---- Search history ----
                Text {
                    x: 16; y: 16
                    visible: hotArea.histCount > 0
                    text: "搜索历史"
                    font.pixelSize: 18
                    font.weight: Font.DemiBold
                    color: Theme.color.onSurfaceColor
                }
                Text {
                    x: parent.width - 60; y: 22
                    visible: hotArea.histCount > 0
                    text: "清除"
                    font.pixelSize: 14
                    color: Theme.color.primary
                    MouseArea { anchors.fill: parent; anchors.margins: -8; onClicked: player.clearSearchHistory() }
                }

                Item {
                    x: 0; y: 56
                    visible: hotArea.histCount > 0
                    width: hotArea.width
                    height: hotArea.histCount * hotArea.rowH

                    Repeater {
                        model: player.searchHistory

                        Item {
                            width: hotArea.width
                            height: hotArea.rowH
                            y: index * hotArea.rowH

                            Rectangle {
                                anchors.fill: parent
                                anchors.leftMargin: 12
                                anchors.rightMargin: 12
                                radius: 8
                                color: hha.pressed ? Theme.color.surfaceContainerHigh : "transparent"
                            }

                            Row {
                                anchors.left: parent.left
                                anchors.leftMargin: 24
                                anchors.verticalCenter: parent.verticalCenter
                                spacing: 8
                                Text {
                                    text: "history"
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

                            // Delete button
                            Text {
                                anchors.right: parent.right
                                anchors.rightMargin: 20
                                anchors.verticalCenter: parent.verticalCenter
                                text: "close"
                                font.family: Theme.iconFont.name
                                font.pixelSize: 18
                                color: Theme.color.onSurfaceVariantColor
                                MouseArea {
                                    anchors.fill: parent
                                    anchors.margins: -8
                                    onClicked: player.removeSearchHistory(index)
                                }
                            }

                            MouseArea {
                                id: hha
                                anchors.fill: parent
                                anchors.rightMargin: 52
                                onClicked: {
                                    query.text = modelData
                                    player.search(modelData)
                                    player.addSearchHistory(modelData)
                                }
                            }
                        }
                    }
                }

                // ---- Hot searches ----
                Text {
                    x: 16; y: hotArea.hotY + 16
                    text: "热门搜索"
                    font.pixelSize: 18
                    font.weight: Font.DemiBold
                    color: Theme.color.onSurfaceColor
                }

                Item {
                    x: 0; y: hotArea.hotY + 56
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
                                    player.addSearchHistory(modelData)
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- Search results (shown when input is not empty) ---
        // Network results take priority; local results are shown as fallback when
        // the network search fails (player.localSearchResults becomes non-empty).
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: query.text.length > 0

            VirtualSongList {
                id: results
                anchors.fill: parent
                visible: player.searchResults && player.searchResults.length > 0
                list: player.searchResults
                onActivated: player.playSearchResult(results.activatedIndex)
            }

            ColumnLayout {
                anchors.fill: parent
                visible: !(player.searchResults && player.searchResults.length > 0) && player.localSearchResults && player.localSearchResults.length > 0
                spacing: 0

                Text {
                    Layout.fillWidth: true
                    Layout.leftMargin: 16
                    Layout.topMargin: 12
                    Layout.bottomMargin: 4
                    text: "本地搜索结果（无网络）"
                    font.pixelSize: 13
                    color: Theme.color.onSurfaceVariantColor
                }

                VirtualSongList {
                    id: localResults
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    list: player.localSearchResults
                    isLocal: true
                    onActivated: player.playLocalSearchResult(localResults.activatedIndex)
                }
            }
        }
    }
}
