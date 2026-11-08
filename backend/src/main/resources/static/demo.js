"use strict";

const byId = (id) => document.getElementById(id);
const workspace = byId("workspace");
const sessions = byId("sessions");
const foods = byId("foods");
const replacementFoods = byId("replacement-foods");
const notice = byId("notice");
const meals = { BREAKFAST: "早餐", LUNCH: "午餐", DINNER: "晚餐", SNACK: "加餐" };
const nutrients = { energy: "热量", protein: "蛋白质", fat: "脂肪", carbohydrate: "碳水" };
let preview;
let editing;
let correctionPreview;
let busy = false;

async function request(path, options) {
  const response = await fetch("/api/v1" + path, { cache: "no-store", ...options });
  const result = await response.json();
  if (!response.ok) {
    const messages = {
      UNAUTHORIZED: "请重新登录", LOGIN_FAILED: "用户名或密码有误",
      FORBIDDEN: "请求被拒绝"
    };
    const error = new Error(result.message || messages[result.code] || result.code || "请求失败");
    error.status = response.status;
    error.code = result.code;
    throw error;
  }
  return result;
}

async function post(path, body) {
  const csrf = await request("/auth/csrf");
  const isForm = body instanceof URLSearchParams;
  return request(path, {
    method: "POST",
    headers: {
      [csrf.headerName]: csrf.token,
      "Content-Type": isForm ? "application/x-www-form-urlencoded" : "application/json"
    },
    body: isForm ? body : JSON.stringify(body)
  });
}

function showLogin() {
  workspace.hidden = true;
  byId("login-panel").hidden = false;
  byId("login").reset();
  for (const id of ["identity", "history", "selection", "totals", "records", "sessions", "foods"]) {
    byId(id).replaceChildren();
  }
  for (const id of ["chat", "search", "new-session"]) byId(id).reset();
  clearPreview();
  closeCorrection();
}

async function run(action) {
  if (busy) return;
  busy = true;
  notice.textContent = "";
  workspace.disabled = true;
  byId("login").querySelector("button").disabled = true;
  try {
    await action();
  } catch (error) {
    if (error.status === 401 && error.code !== "LOGIN_FAILED") showLogin();
    notice.textContent = error.message;
  } finally {
    busy = false;
    workspace.disabled = false;
    byId("login").querySelector("button").disabled = false;
  }
}

function onSubmit(id, action) {
  byId(id).addEventListener("submit", (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const data = Object.fromEntries(new FormData(form));
    run(() => action(data, form));
  });
}

function selectOptions(select, placeholder, entries) {
  select.replaceChildren(new Option(placeholder, ""));
  for (const [id, label] of entries) select.add(new Option(label, id));
}

function currentSession() {
  if (!sessions.value) throw new Error("请先创建或选择会话");
  return "/sessions/" + encodeURIComponent(sessions.value);
}

function clearPreview() {
  preview = undefined;
  byId("preview").textContent = "";
  byId("confirm").hidden = true;
}

function listItem(text) {
  const item = document.createElement("li");
  item.textContent = text;
  return item;
}

function displayFood(food) {
  return `${food.name}（${food.id}）`;
}

async function loadHistory() {
  const history = byId("history");
  const selection = byId("selection");
  history.replaceChildren();
  selection.textContent = "";
  selectOptions(foods, "沿用会话已选食品", []);
  byId("chat").reset();
  byId("search").reset();
  if (!sessions.value) return;

  const { turns, state } = await request(currentSession() + "/history");
  for (const turn of turns) {
    history.append(listItem("你 · " + turn.message), listItem("助手 · " + turn.answer));
  }
  history.scrollTop = history.scrollHeight;
  selection.textContent = state.selectedFoodId == null ? "尚未确认食品"
    : `已选食品 #${state.selectedFoodId} · ${state.weightGrams == null ? "重量待确认" : state.weightGrams + " 克"}`;
  const candidates = await Promise.all(state.candidateFoodIds.map((id) => request("/foods/" + id)));
  selectOptions(foods, "沿用会话已选食品", candidates.map((food) => [food.id, displayFood(food)]));
}

async function loadSessions(preferred) {
  const list = await request("/sessions");
  selectOptions(sessions, "请选择会话", list.map((session) => [session.sessionId, session.title]));
  sessions.value = preferred || list.at(-1)?.sessionId || "";
  await loadHistory();
}

async function showWorkspace() {
  const { username } = await request("/auth/me");
  byId("identity").textContent = "当前账户 · " + username;
  byId("login-panel").hidden = true;
  workspace.hidden = false;
  await loadSessions();
}

function displayTotals(totals) {
  return Object.entries(nutrients).map(([key, label]) => {
    const value = totals[key];
    switch (value.status) {
      case "COMPLETE": return `${label} · ${value.amount} ${value.unit}`;
      case "PARTIAL": return `${label} · 已知小计 ${value.knownSubtotal} ${value.unit}（${value.missingCount} 条缺失）`;
      case "MISSING": return label + " · 数据缺失";
      default: return label + " · 无记录";
    }
  }).join("\n");
}

function nutritionLines(nutrition, labels = nutrients) {
  const values = Object.entries(labels).map(([key, label]) => {
    const { amount, unit } = nutrition[key];
    return label + " · " + (amount == null ? "数据缺失" : amount + " " + unit);
  });
  return [...values, "来源：" + nutrition.source.name];
}

function displayEnergy(nutrition) {
  const { energy } = nutrition;
  return energy.amount == null ? "热量数据缺失" : `${energy.amount} ${energy.unit}`;
}

function recordSummary(details) {
  const { nutrition } = details;
  return [
    details.date, meals[details.mealType], nutrition.name,
    nutrition.weightGrams + " 克", displayEnergy(nutrition),
    details.note ? "备注：" + details.note : null
  ].filter(Boolean).join(" · ");
}

function clearCorrectionPreview() {
  correctionPreview = undefined;
  byId("correction-preview").textContent = "";
  byId("confirm-edit").hidden = true;
}

function closeCorrection() {
  editing = undefined;
  clearCorrectionPreview();
  byId("correction").hidden = true;
  byId("correction-original").textContent = "";
  byId("edit-meal").reset();
  byId("replacement-search").reset();
  selectOptions(replacementFoods, "保留原食品", []);
}

function openCorrection(record) {
  closeCorrection();
  editing = record;
  const { date, mealType, nutrition, note } = record.details;
  const fields = byId("edit-meal").elements;
  fields.date.value = date;
  fields.mealType.value = mealType;
  fields.weight.value = nutrition.weightGrams;
  fields.note.value = note ?? "";
  byId("correction-original").textContent = "原记录 · " + recordSummary(record.details);
  byId("correction").hidden = false;
  fields.weight.focus();
}

async function loadDaily(date) {
  const day = await request("/meals/daily?date=" + encodeURIComponent(date));
  closeCorrection();
  byId("totals").textContent = day.date + "\n" + displayTotals(day.totals);
  byId("records").replaceChildren(...day.records.map((record) => {
    const item = listItem(recordSummary(record.details));
    const edit = document.createElement("button");
    edit.type = "button";
    edit.textContent = "修改";
    edit.addEventListener("click", () => openCorrection(record));
    item.append(" ", edit);
    return item;
  }));
}

async function handleCorrectionFailure(error, originalDate) {
  if (error.code === "RECORD_CONFLICT") {
    closeCorrection();
    byId("daily").elements.date.value = originalDate;
    await loadDaily(originalDate);
    notice.textContent = "记录已被更新。已刷新日报，请从最新记录重新修改。";
  } else if (error.code === "PREVIEW_EXPIRED") {
    clearCorrectionPreview();
    notice.textContent = "修改预览已过期，请重新生成。";
  } else {
    throw error;
  }
}

async function searchFoods(keyword) {
  return request("/foods?keyword=" + encodeURIComponent(keyword));
}

onSubmit("replacement-search", async ({ keyword }) => {
  if (!editing) return;
  const matches = (await searchFoods(keyword))
    .filter((food) => food.id !== editing.details.nutrition.foodId);
  const previousChoice = replacementFoods.value;
  selectOptions(replacementFoods, "保留原食品", matches.map((food) => [food.id, displayFood(food)]));
  if (matches.some((food) => String(food.id) === previousChoice)) {
    replacementFoods.value = previousChoice;
  } else if (previousChoice) {
    restoreOriginalWeight();
    clearCorrectionPreview();
  }
  notice.textContent = matches.length ? `找到 ${matches.length} 个替换候选` : "没有可替换的食品，请更换关键词";
});

function restoreOriginalWeight() {
  byId("edit-meal").elements.weight.value = editing.details.nutrition.weightGrams;
}

replacementFoods.addEventListener("change", () => {
  clearCorrectionPreview();
  if (replacementFoods.value) {
    const weight = byId("edit-meal").elements.weight;
    weight.value = "";
    weight.focus();
    notice.textContent = "请填写替换食品的重量";
  } else {
    restoreOriginalWeight();
    notice.textContent = "";
  }
});

onSubmit("edit-meal", async ({ date, mealType, weight, note, food }) => {
  if (!editing) return;
  const { details, recordId, version } = editing;
  const changes = {};
  if (date !== details.date) changes.date = date;
  if (mealType !== details.mealType) changes.mealType = mealType;
  if (food) changes.foodId = Number(food);
  if (food || Number(weight) !== details.nutrition.weightGrams) changes.weightGrams = Number(weight);
  if (note !== (details.note ?? "")) changes.note = note;
  clearCorrectionPreview();
  if (Object.keys(changes).length === 0) throw new Error("请至少修改一项");
  try {
    correctionPreview = await post("/meals/records/" + recordId + "/correction-previews", {
      expectedVersion: version, ...changes
    });
    const updated = correctionPreview.details;
    const { energy, ...otherNutrients } = nutrients;
    byId("correction-preview").textContent = [
      "修改后 · " + recordSummary(updated),
      ...nutritionLines(updated.nutrition, otherNutrients)
    ].join("\n");
    byId("confirm-edit").hidden = false;
  } catch (error) {
    await handleCorrectionFailure(error, details.date);
  }
});

byId("edit-meal").addEventListener("input", clearCorrectionPreview);
byId("cancel-edit").addEventListener("click", closeCorrection);
byId("confirm-edit").addEventListener("click", () => run(async () => {
  if (!correctionPreview || !editing) return;
  let result;
  try {
    result = await post("/meals/previews/" + correctionPreview.operationId + "/confirm");
  } catch (error) {
    await handleCorrectionFailure(error, editing.details.date);
    return;
  }
  closeCorrection();
  const date = result.details.date;
  byId("daily").elements.date.value = date;
  await loadDaily(date);
  notice.textContent = "记餐已更新";
}));

onSubmit("login", async (data, form) => {
  await post("/auth/login", new URLSearchParams(data));
  form.reset();
  await showWorkspace();
});
byId("logout").addEventListener("click", () => run(async () => {
  await post("/auth/logout");
  showLogin();
}));
onSubmit("new-session", async (data, form) => {
  const created = await post("/sessions", data);
  form.reset();
  clearPreview();
  await loadSessions(created.sessionId);
});
sessions.addEventListener("change", () => run(async () => {
  clearPreview();
  await loadHistory();
}));
onSubmit("search", async ({ keyword }) => {
  const matches = await searchFoods(keyword);
  selectOptions(foods, "请选择搜索结果", matches.map((food) => [food.id, displayFood(food)]));
  notice.textContent = matches.length ? `找到 ${matches.length} 个候选` : "没有匹配食品，请更换关键词";
});
onSubmit("chat", async ({ message, food, weight }) => {
  await post(currentSession() + "/messages", {
    message,
    selectedFoodId: food ? Number(food) : undefined,
    weightGrams: weight ? Number(weight) : undefined
  });
  await loadHistory();
});
onSubmit("meal", async ({ date, mealType }) => {
  currentSession();
  const result = await post("/meals/previews/from-session", { sessionId: sessions.value, date, mealType });
  preview = result;
  const { nutrition } = result.details;
  byId("preview").textContent = [
    date + " · " + meals[mealType],
    nutrition.name + " · " + nutrition.weightGrams + " 克",
    ...nutritionLines(nutrition)
  ].join("\n");
  byId("confirm").hidden = false;
});
byId("confirm").addEventListener("click", () => run(async () => {
  if (!preview) return;
  const result = await post("/meals/previews/" + preview.operationId + "/confirm");
  byId("preview").textContent += "\n已确认";
  byId("confirm").hidden = true;
  preview = undefined;
  const date = result.details.date;
  byId("daily").elements.date.value = date;
  await loadDaily(date);
}));
onSubmit("daily", ({ date }) => loadDaily(date));

const today = new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Shanghai" }).format(new Date());
for (const id of ["meal", "daily"]) byId(id).elements.date.value = today;
run(async () => {
  try { await showWorkspace(); }
  catch (error) { if (error.status !== 401) throw error; }
});
