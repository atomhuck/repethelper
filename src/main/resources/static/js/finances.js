(() => {
  const root = document.querySelector("[data-finance-page]");
  if (!root) return;

  const chart = root.querySelector("[data-chart-scroll]");
  const barsRoot = root.querySelector("[data-chart-bars]");
  const chartError = root.querySelector("[data-chart-error]");
  const tooltip = root.querySelector("[data-chart-tooltip]");
  const monthList = root.querySelector("[data-month-list]");
  const debtList = root.querySelector("[data-debt-list]");
  const debtFilters = root.querySelector("[data-debt-filters]");
  const csrf = document.querySelector('input[name="_csrf"]');
  const money = new Intl.NumberFormat("ru-RU", { style: "currency", currency: "RUB", maximumFractionDigits: 0 });
  const monthName = new Intl.DateTimeFormat("ru-RU", { month: "long", year: "numeric", timeZone: "Europe/Moscow" });
  const monthShort = new Intl.DateTimeFormat("ru-RU", { month: "short", timeZone: "Europe/Moscow" });
  const dateTime = new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "long", hour: "2-digit", minute: "2-digit", timeZone: "Europe/Moscow" });
  const points = new Map();
  const requestCache = new Map();
  let selectedMonth = root.dataset.selectedMonth;
  let monthPage = 0;
  let debtPage = Number(new URL(location.href).searchParams.get("debtPage") || 0);
  let loadingOlder = false;
  let loadingNewer = false;
  let detailsController;

  const monthDate = value => new Date(`${value}-01T12:00:00+03:00`);
  const shiftMonth = (value, delta) => {
    const date = monthDate(value);
    date.setUTCMonth(date.getUTCMonth() + delta);
    return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}`;
  };
  const titleFor = value => {
    const text = monthName.format(monthDate(value));
    return text.charAt(0).toUpperCase() + text.slice(1);
  };
  const shortFor = value => monthShort.format(monthDate(value)).replace(".", "");
  const paymentLabel = value => value === "PAID" ? "Оплачено" : "Не оплачено";
  const lessonsCount = value => {
    const mod100 = Math.abs(value) % 100;
    const mod10 = Math.abs(value) % 10;
    const noun = mod100 >= 11 && mod100 <= 14 ? "занятий"
      : mod10 === 1 ? "занятие"
        : mod10 >= 2 && mod10 <= 4 ? "занятия" : "занятий";
    return `${value} ${noun}`;
  };

  barsRoot.querySelectorAll("[data-month]").forEach(bar => points.set(bar.dataset.month, {
    month: bar.dataset.month,
    expected: Number(bar.dataset.expected),
    received: Number(bar.dataset.received),
    remaining: Number(bar.dataset.remaining)
  }));

  const pointValues = () => [...points.values()].sort((a, b) => a.month.localeCompare(b.month));
  const updateChartScale = () => {
    const max = Math.max(1, ...pointValues().map(point => point.expected));
    barsRoot.querySelectorAll(".finance-bar").forEach(bar => {
      const expected = Number(bar.dataset.expected);
      const received = Number(bar.dataset.received);
      bar.style.setProperty("--total-height", `${Math.max(expected ? 4 : 0, expected / max * 100)}%`);
      bar.style.setProperty("--received-height", `${Math.max(received ? 3 : 0, received / max * 100)}%`);
    });
  };

  const createBar = point => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "finance-bar";
    button.dataset.month = point.month;
    button.dataset.expected = point.expected;
    button.dataset.received = point.received;
    button.dataset.remaining = point.remaining;
    button.setAttribute("aria-pressed", String(point.month === selectedMonth));
    button.setAttribute("aria-label", `${titleFor(point.month)}: получено ${money.format(point.received)}, осталось ${money.format(point.remaining)}`);
    const track = document.createElement("span");
    track.className = "finance-bar-track";
    track.innerHTML = '<span class="finance-bar-remaining"></span><span class="finance-bar-received"></span>';
    const label = document.createElement("span");
    label.className = "finance-bar-label";
    label.textContent = shortFor(point.month);
    const year = document.createElement("span");
    year.className = "finance-bar-year";
    year.textContent = point.month.slice(0, 4);
    button.append(track, label, year);
    return button;
  };

  const rebuildBars = (preserveFromLeft = false) => {
    const oldWidth = barsRoot.scrollWidth;
    const oldLeft = chart.scrollLeft;
    const fragment = document.createDocumentFragment();
    pointValues().forEach(point => fragment.append(createBar(point)));
    barsRoot.replaceChildren(fragment);
    updateSelectedBar();
    updateChartScale();
    chart.scrollLeft = preserveFromLeft ? oldLeft + (barsRoot.scrollWidth - oldWidth) : oldLeft;
  };

  const updateSelectedBar = () => {
    barsRoot.querySelectorAll(".finance-bar").forEach(bar => {
      const selected = bar.dataset.month === selectedMonth;
      bar.classList.toggle("selected", selected);
      bar.setAttribute("aria-pressed", String(selected));
    });
    root.querySelector("[data-chart-current]").disabled = selectedMonth === root.dataset.currentMonth;
  };

  const updateSummary = point => {
    if (!point) return;
    root.querySelector("[data-finance-expected]").textContent = money.format(point.expected);
    root.querySelector("[data-finance-received]").textContent = money.format(point.received);
    root.querySelector("[data-finance-remaining]").textContent = money.format(point.remaining);
    root.querySelector("[data-finance-month-title]").textContent = titleFor(point.month);
    root.querySelector("[data-month-details-title]").textContent = `Занятия за ${titleFor(point.month).toLowerCase()}`;
  };

  const fetchMonths = async (end, count = 12) => {
    const key = `${end}:${count}`;
    if (!requestCache.has(key)) {
      requestCache.set(key, fetch(`/api/teacher/finances/months?end=${encodeURIComponent(end)}&count=${count}`, {
        headers: { Accept: "application/json" }
      }).then(response => {
        if (!response.ok) throw new Error("Не удалось загрузить статистику");
        return response.json();
      }).catch(error => {
        requestCache.delete(key);
        throw error;
      }));
    }
    return requestCache.get(key);
  };

  const loadOlder = async () => {
    if (loadingOlder) return;
    const earliest = pointValues()[0]?.month;
    if (!earliest || earliest === "2020-01") return;
    loadingOlder = true;
    try {
      const data = await fetchMonths(shiftMonth(earliest, -1));
      data.months.forEach(point => points.set(point.month, point));
      rebuildBars(true);
    } catch (error) {
      chartError.textContent = error.message;
      chartError.hidden = false;
    } finally { loadingOlder = false; }
  };

  const loadNewer = async () => {
    if (loadingNewer) return;
    const latest = pointValues().at(-1)?.month;
    if (!latest || latest >= root.dataset.currentMonth) return;
    loadingNewer = true;
    try {
      let end = shiftMonth(latest, 12);
      if (end > root.dataset.currentMonth) end = root.dataset.currentMonth;
      const data = await fetchMonths(end);
      data.months.forEach(point => points.set(point.month, point));
      rebuildBars(false);
    } catch (error) {
      chartError.textContent = error.message;
      chartError.hidden = false;
    } finally { loadingNewer = false; }
  };

  const emptyState = (title, copy) => {
    const empty = document.createElement("div");
    empty.className = "empty compact";
    empty.dataset.emptyState = "";
    const heading = document.createElement("h3");
    heading.textContent = title;
    const paragraph = document.createElement("p");
    paragraph.textContent = copy;
    empty.append(heading, paragraph);
    return empty;
  };

  const csrfInput = () => {
    if (!csrf) return null;
    const input = document.createElement("input");
    input.type = "hidden";
    input.name = csrf.name;
    input.value = csrf.value;
    return input;
  };

  const paymentForm = (row, compactText = false) => {
    const form = document.createElement("form");
    form.className = "finance-payment-form";
    form.method = "post";
    form.action = `/teacher/finances/lessons/${row.lessonId}/payment-status`;
    const status = document.createElement("input");
    status.type = "hidden";
    status.name = "status";
    status.value = row.paymentStatus === "PAID" ? "UNPAID" : "PAID";
    form.append(status);
    if (row.paymentRecordId) {
      const expected = document.createElement("input");
      expected.type = "hidden";
      expected.name = "expectedPaymentRecordId";
      expected.value = row.paymentRecordId;
      form.append(expected);
    }
    const token = csrfInput();
    if (token) form.append(token);
    const button = document.createElement("button");
    button.type = "submit";
    button.className = `button compact ${row.paymentStatus === "PAID" ? "ghost" : "primary"}`;
    button.textContent = row.paymentStatus === "PAID" ? "Вернуть в неоплаченные" : (compactText ? "Деньги получены" : "Деньги получены");
    form.append(button);
    return form;
  };

  const createMonthRow = row => {
    const article = document.createElement("article");
    article.className = "finance-row";
    if (row.cancelled) article.classList.add("cancelled");
    if (row.lessonId) article.dataset.lessonId = row.lessonId;
    const avatar = document.createElement("span");
    avatar.className = `finance-row-avatar${row.deleted ? " deleted" : ""}`;
    avatar.textContent = row.deleted ? "—" : (row.studentName?.charAt(0) || "У");
    const main = document.createElement("div");
    main.className = "finance-row-main";
    const name = document.createElement("b");
    name.textContent = row.deleted ? "Удалённое занятие" : row.studentName;
    const when = document.createElement("span");
    when.textContent = `${dateTime.format(new Date(row.startAt))}${row.deleted ? "" : ` · ${row.durationMinutes} мин`}`;
    const note = document.createElement("small");
    note.textContent = row.deleted ? "Финансовая запись сохранена без данных ученика"
      : row.cancelled ? "Отменённое занятие"
        : (row.completed ? "Занятие завершено" : "Будущее занятие");
    main.append(name, when, note);
    const value = document.createElement("div");
    value.className = "finance-row-value";
    const amount = document.createElement("strong");
    amount.textContent = money.format(row.amountRubles);
    const pill = document.createElement("span");
    pill.className = `payment-pill ${row.paymentStatus === "PAID" ? "paid" : "unpaid"}`;
    pill.textContent = paymentLabel(row.paymentStatus);
    value.append(amount);
    if (row.paidBySubscription) {
      const source = document.createElement("span");
      source.className = "payment-pill subscription-paid";
      source.textContent = "Абонемент";
      value.append(source);
    }
    value.append(pill);
    article.append(avatar, main, value);
    if (!row.deleted) {
      const actions = document.createElement("div");
      actions.className = "finance-row-actions";
      const open = document.createElement("a");
      open.className = "icon-button";
      open.href = `/lessons/${row.lessonId}`;
      open.setAttribute("aria-label", "Открыть занятие");
      open.innerHTML = '<svg class="ui-icon" aria-hidden="true"><use href="/brand/ui-icons.svg#arrow-right"></use></svg>';
      actions.append(open);
      if (row.paidBySubscription) {
        const sourceLink = document.createElement("a");
        sourceLink.className = "button ghost compact";
        sourceLink.href = `/lessons/${row.lessonId}`;
        sourceLink.textContent = "Абонемент";
        actions.append(sourceLink);
      } else actions.append(paymentForm(row));
      article.append(actions);
    }
    return article;
  };

  const updateMonthPagination = data => {
    const pagination = root.querySelector("[data-month-pagination]");
    pagination.hidden = data.totalPages <= 1;
    pagination.dataset.page = data.page;
    pagination.dataset.totalPages = data.totalPages;
    pagination.querySelector('[data-month-page="prev"]').disabled = !data.hasPrevious;
    pagination.querySelector('[data-month-page="next"]').disabled = !data.hasNext;
    pagination.querySelector("[data-month-page-label]").textContent = data.totalPages
      ? `Страница ${data.page + 1} из ${data.totalPages}` : "";
  };

  const loadMonthDetails = async (month, page = 0) => {
    detailsController?.abort();
    detailsController = new AbortController();
    monthList.classList.add("finance-loading");
    try {
      const response = await fetch(`/api/teacher/finances/months/${month}/lessons?page=${page}&size=20`, {
        headers: { Accept: "application/json" }, signal: detailsController.signal
      });
      if (!response.ok) throw new Error("Не удалось загрузить занятия месяца");
      const data = await response.json();
      if (month !== selectedMonth) return;
      const fragment = document.createDocumentFragment();
      if (!data.content.length) fragment.append(emptyState("Нет занятий со стоимостью", "В этом месяце финансовых записей пока нет."));
      else data.content.forEach(row => fragment.append(createMonthRow(row)));
      monthList.replaceChildren(fragment);
      monthPage = data.page;
      updateMonthPagination(data);
      points.set(data.summary.month, data.summary);
      updateSummary(data.summary);
      rebuildBars(false);
    } catch (error) {
      if (error.name !== "AbortError") monthList.replaceChildren(emptyState("Не удалось загрузить данные", "Проверьте соединение и попробуйте ещё раз."));
    } finally { monthList.classList.remove("finance-loading"); }
  };

  const createDebtRow = row => {
    const article = document.createElement("article");
    article.className = "finance-row debt";
    article.dataset.lessonId = row.lessonId;
    const avatar = document.createElement("span");
    avatar.className = "finance-row-avatar";
    avatar.textContent = row.studentName?.charAt(0) || "У";
    const main = document.createElement("div");
    main.className = "finance-row-main";
    const name = document.createElement("b");
    name.textContent = row.studentName;
    const when = document.createElement("span");
    when.textContent = `${dateTime.format(new Date(row.startAt))} · ${row.durationMinutes} мин`;
    const overdue = document.createElement("small");
    overdue.className = "overdue";
    overdue.textContent = row.overdueDays === 0 ? "Оплата ожидается сегодня" : `Просрочено: ${row.overdueDays} дн.`;
    main.append(name, when, overdue);
    const value = document.createElement("div");
    value.className = "finance-row-value";
    const amount = document.createElement("strong");
    amount.textContent = money.format(row.amountRubles);
    const pill = document.createElement("span");
    pill.className = "payment-pill unpaid";
    pill.textContent = "Не оплачено";
    value.append(amount, pill);
    const actions = document.createElement("div");
    actions.className = "finance-row-actions";
    const open = document.createElement("a");
    open.className = "icon-button";
    open.href = `/lessons/${row.lessonId}`;
    open.setAttribute("aria-label", "Открыть занятие");
    open.innerHTML = '<svg class="ui-icon" aria-hidden="true"><use href="/brand/ui-icons.svg#arrow-right"></use></svg>';
    actions.append(open, paymentForm({ ...row, paymentStatus: "UNPAID" }, true));
    article.append(avatar, main, value, actions);
    return article;
  };

  const updateDebtPagination = data => {
    const pagination = root.querySelector("[data-debt-pagination]");
    pagination.hidden = data.totalPages <= 1;
    pagination.dataset.page = data.page;
    pagination.dataset.totalPages = data.totalPages;
    pagination.replaceChildren();
    if (data.totalPages <= 1) return;
    const prev = document.createElement("button");
    prev.type = "button"; prev.className = "button ghost compact"; prev.textContent = "Назад";
    prev.dataset.debtPage = String(data.page - 1); prev.disabled = !data.hasPrevious;
    const label = document.createElement("span");
    label.textContent = `Страница ${data.page + 1} из ${data.totalPages}`;
    const next = document.createElement("button");
    next.type = "button"; next.className = "button ghost compact"; next.textContent = "Далее";
    next.dataset.debtPage = String(data.page + 1); next.disabled = !data.hasNext;
    pagination.append(prev, label, next);
  };

  const loadDebts = async (page = 0) => {
    const params = new URLSearchParams(new FormData(debtFilters));
    params.set("page", String(page));
    params.set("size", "20");
    params.delete("month");
    debtList.classList.add("finance-loading");
    try {
      const response = await fetch(`/api/teacher/finances/debts?${params}`, { headers: { Accept: "application/json" } });
      if (!response.ok) throw new Error();
      const data = await response.json();
      const fragment = document.createDocumentFragment();
      if (!data.content.length) fragment.append(emptyState("Просроченных оплат нет", "Все завершившиеся занятия оплачены."));
      else data.content.forEach(row => fragment.append(createDebtRow(row)));
      debtList.replaceChildren(fragment);
      debtPage = data.page;
      root.querySelector("[data-debt-count]").textContent = lessonsCount(data.totalElements);
      root.querySelector("[data-debt-amount]").textContent = money.format(data.totalAmount);
      updateDebtPagination(data);
      const url = new URL(location.href);
      const student = debtFilters.elements.studentId.value;
      const period = debtFilters.elements.period.value;
      student ? url.searchParams.set("studentId", student) : url.searchParams.delete("studentId");
      period !== "ALL" ? url.searchParams.set("period", period) : url.searchParams.delete("period");
      page ? url.searchParams.set("debtPage", page) : url.searchParams.delete("debtPage");
      history.replaceState({}, "", url);
    } catch {
      debtList.replaceChildren(emptyState("Не удалось загрузить долги", "Проверьте соединение и повторите попытку."));
    } finally { debtList.classList.remove("finance-loading"); }
  };

  const showToast = (message, undo) => {
    const region = document.querySelector("[data-finance-toasts]");
    const toast = document.createElement("div");
    toast.className = "finance-toast";
    const text = document.createElement("span");
    text.textContent = message;
    toast.append(text);
    if (undo) {
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = "Отменить";
      button.addEventListener("click", async () => {
        button.disabled = true;
        try { await undo(); toast.remove(); }
        catch { button.disabled = false; text.textContent = "Не удалось отменить: данные уже изменились"; }
      });
      toast.append(button);
    }
    region.replaceChildren(toast);
    setTimeout(() => toast.remove(), 9000);
  };

  const sendPayment = async (lessonId, status, expectedPaymentRecordId) => {
    const data = new FormData();
    data.set("status", status);
    if (expectedPaymentRecordId) data.set("expectedPaymentRecordId", expectedPaymentRecordId);
    if (csrf) data.set(csrf.name, csrf.value);
    const response = await fetch(`/teacher/finances/lessons/${lessonId}/payment-status`, {
      method: "POST", headers: { Accept: "application/json" }, body: data
    });
    if (!response.ok) throw new Error(response.status === 409 ? "Данные уже изменились в другой вкладке" : "Не удалось изменить оплату");
    const result = await response.json();
    points.set(result.month.month, result.month);
    rebuildBars(false);
    if (result.month.month === selectedMonth) {
      updateSummary(result.month);
      await loadMonthDetails(selectedMonth, monthPage);
    }
    await loadDebts(Math.max(0, debtPage));
    return result;
  };

  const selectMonth = async (month, updateHistory = true) => {
    selectedMonth = month;
    root.dataset.selectedMonth = month;
    updateSelectedBar();
    updateSummary(points.get(month));
    monthPage = 0;
    if (updateHistory) {
      const url = new URL(location.href);
      url.searchParams.set("month", month);
      history.pushState({ month }, "", url);
    }
    await loadMonthDetails(month, 0);
  };

  barsRoot.addEventListener("click", event => {
    const bar = event.target.closest("[data-month]");
    if (bar && !chart.classList.contains("dragging")) selectMonth(bar.dataset.month);
  });

  barsRoot.addEventListener("pointerover", event => {
    const bar = event.target.closest("[data-month]");
    if (!bar) return;
    tooltip.textContent = `${titleFor(bar.dataset.month)} · получено ${money.format(Number(bar.dataset.received))} · осталось ${money.format(Number(bar.dataset.remaining))}`;
    const rect = bar.getBoundingClientRect();
    tooltip.style.left = `${Math.min(innerWidth - 230, Math.max(8, rect.left + rect.width / 2 - 90))}px`;
    tooltip.style.top = `${Math.max(8, rect.top - 52)}px`;
    tooltip.hidden = false;
  });
  barsRoot.addEventListener("pointerout", event => { if (event.target.closest("[data-month]")) tooltip.hidden = true; });

  chart.addEventListener("scroll", () => {
    if (chart.scrollLeft < 80) loadOlder();
    if (chart.scrollWidth - chart.clientWidth - chart.scrollLeft < 80) loadNewer();
  }, { passive: true });
  chart.addEventListener("wheel", event => {
    if (!event.shiftKey || Math.abs(event.deltaY) < Math.abs(event.deltaX)) return;
    event.preventDefault();
    chart.scrollLeft += event.deltaY;
  }, { passive: false });

  let dragStartX = 0;
  let dragStartScroll = 0;
  let dragged = false;
  chart.addEventListener("pointerdown", event => {
    if (event.pointerType === "touch" || event.button !== 0) return;
    dragStartX = event.clientX; dragStartScroll = chart.scrollLeft; dragged = false;
    chart.setPointerCapture(event.pointerId);
  });
  chart.addEventListener("pointermove", event => {
    if (!chart.hasPointerCapture(event.pointerId)) return;
    const distance = event.clientX - dragStartX;
    if (Math.abs(distance) > 5) { dragged = true; chart.classList.add("dragging"); }
    if (dragged) chart.scrollLeft = dragStartScroll - distance;
  });
  chart.addEventListener("pointerup", event => {
    if (chart.hasPointerCapture(event.pointerId)) chart.releasePointerCapture(event.pointerId);
    setTimeout(() => chart.classList.remove("dragging"), 0);
  });

  root.querySelector("[data-chart-older]").addEventListener("click", async () => {
    if (chart.scrollLeft < chart.clientWidth * .25) await loadOlder();
    chart.scrollBy({ left: -chart.clientWidth * .8, behavior: "smooth" });
  });
  root.querySelector("[data-chart-newer]").addEventListener("click", async () => {
    if (chart.scrollWidth - chart.clientWidth - chart.scrollLeft < chart.clientWidth * .25) await loadNewer();
    chart.scrollBy({ left: chart.clientWidth * .8, behavior: "smooth" });
  });
  root.querySelector("[data-chart-current]").addEventListener("click", async () => {
    if (!points.has(root.dataset.currentMonth)) {
      const data = await fetchMonths(root.dataset.currentMonth);
      data.months.forEach(point => points.set(point.month, point));
      rebuildBars(false);
    }
    await selectMonth(root.dataset.currentMonth);
    barsRoot.querySelector(`[data-month="${root.dataset.currentMonth}"]`)?.scrollIntoView({ behavior: "smooth", inline: "end", block: "nearest" });
  });

  root.querySelector("[data-month-pagination]").addEventListener("click", event => {
    const button = event.target.closest("[data-month-page]");
    if (!button || button.disabled) return;
    loadMonthDetails(selectedMonth, button.dataset.monthPage === "prev" ? monthPage - 1 : monthPage + 1);
  });

  root.querySelector("[data-debt-pagination]").addEventListener("click", event => {
    const control = event.target.closest("[data-debt-page]");
    if (control && !control.disabled) { event.preventDefault(); loadDebts(Number(control.dataset.debtPage)); }
  });

  debtFilters.addEventListener("submit", event => { event.preventDefault(); loadDebts(0); });

  root.addEventListener("submit", async event => {
    const form = event.target.closest(".finance-payment-form");
    if (!form) return;
    event.preventDefault();
    const button = form.querySelector("button[type=submit]");
    const lessonId = form.action.match(/lessons\/(\d+)/)?.[1];
    const data = new FormData(form);
    if (!lessonId) return;
    button.disabled = true;
    try {
      const result = await sendPayment(lessonId, data.get("status"), data.get("expectedPaymentRecordId"));
      if (result.status === "PAID" && result.paymentRecordId) {
        showToast("Оплата отмечена", () => sendPayment(lessonId, "UNPAID", result.paymentRecordId));
      } else showToast("Занятие отмечено неоплаченным");
    } catch (error) {
      showToast(error.message);
      button.disabled = false;
    }
  });

  addEventListener("popstate", () => {
    const month = new URL(location.href).searchParams.get("month") || root.dataset.currentMonth;
    if (month !== selectedMonth) selectMonth(month, false);
  });

  updateChartScale();
  requestAnimationFrame(() => {
    const selected = barsRoot.querySelector(`[data-month="${selectedMonth}"]`);
    selected?.scrollIntoView({ inline: "end", block: "nearest" });
  });
})();
