Rectangle {
    property string label: "pill"
    property color tint: "#3050a0"

    width: 140
    height: 44
    radius: 22
    color: tint

    Text {
        anchors.centerIn: parent
        text: label
        color: "#ffffff"
        fontSize: 16
    }
}
