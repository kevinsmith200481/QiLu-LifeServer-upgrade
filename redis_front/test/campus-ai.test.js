const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

function loadMethods() {
  let options;
  const context = {
    Vue: function Vue(appOptions) {
      options = appOptions;
    },
    campusStateMixin: {},
    campusApi: {},
    campusUiConfig: {},
    CampusWebSocketManager: function CampusWebSocketManager() {},
    axios: {},
    window: {},
    document: {},
    console
  };
  const source = fs.readFileSync(
    path.join(__dirname, "../html/campus/js/campus-app.js"),
    "utf8"
  );
  vm.runInNewContext(source, context, { filename: "campus-app.js" });
  return { methods: options.methods, context };
}

test("send button ignores PointerEvent and submits the text area question", async () => {
  const { methods, context } = loadMethods();
  let submittedQuestion;
  context.axios.post = async (url, payload) => {
    submittedQuestion = payload.question;
    return { data: { sessionId: 147, response: { answer: "ok" } } };
  };
  const component = {
    aiQuestion: "\u6211\u7684\u9884\u7ea6\u8bb0\u5f55\u53d1\u751f\u4e86\u4ec0\u4e48",
    aiSessionId: null,
    selectedCategoryId: null,
    chatMessages: [],
    withSubmit: async (key, action) => action(),
    notify: () => {},
    scrollChatToEnd: () => {},
    callApi: action => action(),
    loadAiSessions: async () => {},
    aiResponseToMessage: response => ({ role: "assistant", text: response.answer })
  };

  await methods.sendAiQuestion.call(component, { type: "click" });

  assert.equal(submittedQuestion, "\u6211\u7684\u9884\u7ea6\u8bb0\u5f55\u53d1\u751f\u4e86\u4ec0\u4e48");
  assert.equal(component.chatMessages[0].text, submittedQuestion);
  assert.equal(component.aiQuestion, "");
});
