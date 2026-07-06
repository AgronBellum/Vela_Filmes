const urlParams = new URL(window.location.href);
const params = new URLSearchParams(urlParams.search);

const appVersion = params.get("version");
const apiUrl = `https://app.megafrixapi.com/TV/${appVersion}/`;

var itemSelected = [];

function startApp() {
    itemSelected = $();
    $.ajax({
        url: apiUrl,
    }).done(function (data) {

        $("#app").html(data);

    }).fail(function (jqXHR, textStatus, msg) {
        errorStart();
    });
}

function errorStart() {
    $("#loader .error").css("display", "flex");
    $("#loader img").css("display", "none");

    itemSelected = $("#loader .button");

}

let holdTimeout = null;
let isHolding = false;

$(document).on('keydown', (e) => {
    //console.log(e.keyCode);


    switch (e.keyCode) {
        case 40:
            e.preventDefault();
            controlMove('down');
            break;
        case 38:
            e.preventDefault();
            controlMove('up');
            break;
        case 37:
            e.preventDefault();
            controlMove('left');
            break;
        case 39:
            e.preventDefault();
            controlMove('right');
            break;
        case 13:

            controlOk();



            // console.log("keydownload");
            // return;
            // e.preventDefault();
            // isHolding = false;
            // isKeyActive = true;

            // // Se segurar mais de 500ms, conta como "segurando"
            // holdTimeout = setTimeout(() => {
            //     isHolding = true;
            //     controlHold(); // ação de segurar
            // }, 500);

            break;
        case 27:
            e.preventDefault();
            pressBackAction();
            break;
        default:
            break;
    }

    //MegaFlix.showToast(e.keyCode);
});

// $(document).on('keyup', (e) => {
//     switch (e.keyCode) {
//         case 13:
//             console.log("keyuppp");

//             return;
//             clearTimeout(holdTimeout);

//             if (!isHolding) {
//                 controlOk(); // clique curto
//             } else {
//                 console.log("Soltou após segurar");
//             }

//             // libera a tecla pra permitir o próximo clique
//             isKeyActive = false;
//             isHolding = false;
//             break;
//     }
// });

/*$(document).on('keydown', (e) => {
    //console.log(e.keyCode);
    e.preventDefault();

    switch (e.keyCode) {
            case 13:
                controlOk();
                return;
            case 27:
                pressBackAction();
                return;
    }

    controlMove(e.keyCode);
});*/

function controlOk() {
    itemSelected.click();
}

$(document).on("click", "#loader .button", function (e) {
    $("#loader .error").css("display", "none");
    $("#loader img").css("display", "flex");
    startApp();
});

$(function () {
    console.log('desdgracaaa');
    startApp();
    $("#loader .version").text("TV V" + appVersion);
});