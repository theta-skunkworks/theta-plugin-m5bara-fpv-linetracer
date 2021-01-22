var READYSTATE_COMPLETED = 4;
var HTTP_STATUS_OK = 200;
var POST = 'POST';
var CONTENT_TYPE = 'content-Type';
var TYPE_JSON = 'application/json';
var COMMAND = 'preview/commands/execute';
var status;
function updatePreviwFrame(){
  var command = {
    name: 'camera.getPreviewFrame'
  };
  var xmlHttpRequest = new XMLHttpRequest();
  xmlHttpRequest.onreadystatechange = function() {
    if (this.readyState === READYSTATE_COMPLETED &&
      this.status === HTTP_STATUS_OK) {
      var reader = new FileReader();
      reader.onloadend = function onLoad() {
        var img = document.getElementById('lvimg');
        img.src = reader.result;
      };
      reader.readAsDataURL(this.response);
      repeat();
    }
  };
  xmlHttpRequest.open(POST, COMMAND, true);
  xmlHttpRequest.setRequestHeader(CONTENT_TYPE, TYPE_JSON);
  xmlHttpRequest.responseType = 'blob';
  xmlHttpRequest.send(JSON.stringify(command));
}
function repeat() {
  const d1 = new Date();
  while (true) {
    const d2 = new Date();
    if (d2 - d1 > 30) {
      break;
    }
  }
  updatePreviwFrame();
}
