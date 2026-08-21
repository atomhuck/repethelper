(() => {
  const start = document.getElementById("startAt");
  const label = document.getElementById("weekly-label");
  const student = document.getElementById("lesson-student");
  const price = document.getElementById("lesson-price");
  const priceHint = document.getElementById("price-hint");
  const singleFields = document.getElementById("single-price-fields");
  const subscriptionFields = document.getElementById("new-subscription-fields");
  const subscriptionCount = document.getElementById("subscription-count");
  const subscriptionTotal = document.getElementById("subscription-total");
  const subscriptionPreview = document.getElementById("subscription-preview");
  const weeklyCountField = document.getElementById("weekly-count-field");
  const weeklyCount = document.getElementById("weekly-lesson-count");
  const weeklyCountHint = document.getElementById("weekly-count-hint");
  const balanceHint = document.getElementById("subscription-balance-hint");
  const useSubscriptionChoice = document.getElementById("use-subscription-choice");
  const seriesSubscriptionSetting = document.getElementById("series-subscription-setting");
  const paymentModes = [...document.querySelectorAll('input[name="paymentMode"]')];
  const recurrenceInputs = [...document.querySelectorAll('input[name="recurrence"]')];
  if (!start || !student || !label) return;

  let priceWasEnteredManually = Boolean(price?.value);
  let weeklyCountWasEnteredManually = Boolean(weeklyCount?.value);
  const digitsNumber = value => Number(String(value || "").replace(/[^0-9]/g, ""));
  const weekdays = ["воскресеньям", "понедельникам", "вторникам", "средам", "четвергам", "пятницам", "субботам"];

  const updateWeeklyLabel = () => {
    if (!start.value) {
      label.textContent = "Каждую неделю";
      return;
    }
    const [year, month, day] = start.value.slice(0, 10).split("-").map(Number);
    label.textContent = `Каждую неделю по ${weekdays[new Date(year, month - 1, day).getDay()]}`;
  };

  const currentPaymentMode = () => paymentModes.find(input => input.checked)?.value || "SINGLE";
  const selectedStudentAvailable = () => Number(student.selectedOptions[0]?.dataset.subscriptionAvailable || 0);
  const plannedSubscriptionCount = () => {
    const mode = currentPaymentMode();
    return mode === "CREATE_SUBSCRIPTION" ? digitsNumber(subscriptionCount?.value)
      : mode === "USE_SUBSCRIPTION" ? selectedStudentAvailable() : 0;
  };

  const updateSubscriptionPreview = () => {
    if (!subscriptionPreview) return;
    const count = digitsNumber(subscriptionCount?.value);
    const total = digitsNumber(subscriptionTotal?.value);
    if (!Number.isInteger(count) || count < 1 || count > 100 || !Number.isInteger(total) || total < count) {
      subscriptionPreview.textContent = "Укажите от 1 до 100 занятий и общую стоимость не меньше количества занятий.";
      return;
    }
    if (total > count * 1_000_000) {
      subscriptionPreview.textContent = "На одно занятие должно приходиться не больше 1 000 000 ₽.";
      return;
    }
    const base = Math.floor(total / count);
    const remainder = total % count;
    subscriptionPreview.textContent = remainder
      ? `${count} занятий · ${remainder} по ${(base + 1).toLocaleString("ru-RU")} ₽ и ${count - remainder} по ${base.toLocaleString("ru-RU")} ₽`
      : `${count} занятий · по ${base.toLocaleString("ru-RU")} ₽`;
  };

  const setAutomaticWeeklyCount = count => {
    if (!weeklyCount || weeklyCountWasEnteredManually || !count) return;
    weeklyCount.value = String(Math.max(1, Math.min(104, count)));
  };

  const updateWeeklyCountUi = () => {
    const weekly = recurrenceInputs.some(input => input.checked && input.value === "WEEKLY");
    if (!weeklyCountField || !weeklyCount || !weeklyCountHint) return;
    weeklyCountField.hidden = !weekly;
    weeklyCount.disabled = !weekly;
    if (!weekly) return;
    const mode = currentPaymentMode();
    const paid = plannedSubscriptionCount();
    if (mode === "SINGLE") setAutomaticWeeklyCount(4);
    else setAutomaticWeeklyCount(paid);
    const count = digitsNumber(weeklyCount.value);
    if (!count || count < 1 || count > 104) {
      weeklyCountHint.textContent = "Укажите от 1 до 104 занятий.";
    } else if (mode === "SINGLE") {
      weeklyCountHint.textContent = `Будет добавлено ${count} занятий: по одному каждую неделю.`;
    } else if (count > paid) {
      weeklyCountHint.textContent = `Оплачено по абонементу: ${paid} · будет добавлено: ${count} · ${count - paid} последующих занятий будут неоплаченными.`;
    } else {
      weeklyCountHint.textContent = `Оплачено по абонементу: ${paid} · будет добавлено: ${count}.`;
    }
  };

  const updatePaymentUi = () => {
    const mode = currentPaymentMode();
    if (singleFields) singleFields.hidden = mode !== "SINGLE";
    if (subscriptionFields) subscriptionFields.hidden = mode !== "CREATE_SUBSCRIPTION";
    if (price) price.disabled = mode !== "SINGLE";
    if (subscriptionCount) subscriptionCount.required = mode === "CREATE_SUBSCRIPTION";
    if (subscriptionTotal) subscriptionTotal.required = mode === "CREATE_SUBSCRIPTION";
    const weekly = recurrenceInputs.some(input => input.checked && input.value === "WEEKLY");
    if (seriesSubscriptionSetting) seriesSubscriptionSetting.hidden = mode === "SINGLE" || !weekly;
    updateSubscriptionPreview();
    updateWeeklyCountUi();
  };

  start.addEventListener("input", updateWeeklyLabel);
  price?.addEventListener("input", () => {
    priceWasEnteredManually = true;
    if (priceHint) priceHint.textContent = price.value ? "Стоимость видна только вам" : "Необязательно · видно только вам";
  });
  paymentModes.forEach(input => input.addEventListener("change", updatePaymentUi));
  recurrenceInputs.forEach(input => input.addEventListener("change", updatePaymentUi));
  subscriptionCount?.addEventListener("input", () => { updateSubscriptionPreview(); updateWeeklyCountUi(); });
  subscriptionTotal?.addEventListener("input", updateSubscriptionPreview);
  weeklyCount?.addEventListener("input", () => {
    weeklyCountWasEnteredManually = weeklyCount.value !== "";
    updateWeeklyCountUi();
  });
  student.addEventListener("change", () => {
    const suggested = student.selectedOptions[0]?.dataset.lastPrice;
    const available = selectedStudentAvailable();
    if (balanceHint) balanceHint.textContent = available > 0 ? `Доступно ${available} занятий` : "Свободных занятий в абонементе нет";
    const useInput = useSubscriptionChoice?.querySelector("input");
    if (useInput) {
      useInput.disabled = available < 1;
      if (available > 0 && !priceWasEnteredManually && currentPaymentMode() === "SINGLE") useInput.checked = true;
      if (available < 1 && useInput.checked) paymentModes.find(input => input.value === "SINGLE").checked = true;
    }
    if (price && (!priceWasEnteredManually || !price.value)) {
      price.value = suggested || "";
      priceWasEnteredManually = false;
    }
    if (priceHint) priceHint.textContent = suggested
      ? `Последняя стоимость для этого ученика — ${Number(suggested).toLocaleString("ru-RU")} ₽`
      : "Для этого ученика предыдущая стоимость не найдена";
    updatePaymentUi();
  });

  const bindCopy = (selector, fallback) => document.querySelector(selector)?.addEventListener("click", async event => {
    await navigator.clipboard.writeText(event.currentTarget.dataset.value);
    event.currentTarget.textContent = "Скопировано";
    setTimeout(() => { event.currentTarget.textContent = fallback; }, 1500);
  });
  bindCopy(".copy-invite-code", "Скопировать код");
  bindCopy(".copy-invite-link", "Скопировать ссылку");

  updateWeeklyLabel();
  if (student.value) student.dispatchEvent(new Event("change"));
  updatePaymentUi();
})();
