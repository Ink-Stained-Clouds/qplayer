import QtQuick

// Container component: child objects written inside a Panel are redirected into
// the inner Column via `default property alias`.
Rectangle {
    default property alias content: body.children
    property string title: ""

    width: 420
    height: 160
    color: "#2a2a3a"
    radius: 10

    Text {
        x: 12; y: 10
        text: parent.title
        color: "#a0c0ff"
        fontSize: 20
    }

    Column {
        id: body
        x: 12
        y: 44
        spacing: 6
    }
}
