(() => {
  const cache = new Map();
  const maxCachedMonths = 6;
  const viewStorageKey = "repethelper-calendar-view";
  let activeRequest = null;

  const remember = (url, html) => {
    cache.delete(url);
    cache.set(url, html);
    while (cache.size > maxCachedMonths) cache.delete(cache.keys().next().value);
  };

  const parsePanel = html => {
    const template = document.createElement("template");
    template.innerHTML = html.trim();
    return template.content.firstElementChild;
  };

  const replaceCalendar = (current, html, canonicalUrl, action) => {
    const next = parsePanel(html);
    if (!next?.classList.contains("calendar-workspace")) throw new Error("Invalid calendar response");
    const agenda = document.querySelector("[data-calendar-agenda]");
    const agendaTemplate = next.querySelector("[data-calendar-agenda-template]");
    if (agenda && agendaTemplate?.content) {
      agenda.replaceChildren(agendaTemplate.content.cloneNode(true));
    }
    current.replaceWith(next);
    if (canonicalUrl) history.pushState({ calendar: true }, "", canonicalUrl);
    document.dispatchEvent(new CustomEvent("calendar:updated", { detail: { panel: next } }));
    next.querySelector("[data-calendar-status]").textContent = `${next.querySelector("h2")?.textContent || "Календарь"} загружен`;
    next.querySelector(`[data-calendar-action="${action}"]`)?.focus();
  };

  const load = async (panel, fragmentUrl, canonicalUrl, action, pushState) => {
    activeRequest?.abort();
    const controller = new AbortController();
    activeRequest = controller;
    panel.setAttribute("aria-busy", "true");
    panel.classList.add("is-loading");
    try {
      let html = cache.get(fragmentUrl);
      if (!html) {
        const response = await fetch(fragmentUrl, {
          headers: { Accept: "text/html", "X-Requested-With": "XMLHttpRequest" },
          credentials: "same-origin",
          signal: controller.signal
        });
        if (!response.ok) throw new Error(`Calendar request failed: ${response.status}`);
        html = await response.text();
        remember(fragmentUrl, html);
      }
      if (controller.signal.aborted) return;
      replaceCalendar(panel, html, pushState ? canonicalUrl : null, action);
    } catch (error) {
      if (error.name !== "AbortError") window.location.assign(canonicalUrl);
    } finally {
      if (activeRequest === controller) activeRequest = null;
      panel.removeAttribute("aria-busy");
      panel.classList.remove("is-loading");
    }
  };

  document.addEventListener("click", event => {
    const link = event.target.closest("[data-calendar-link]");
    if (!link || event.defaultPrevented || (event.button != null && event.button !== 0) || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    const panel = link.closest(".calendar-workspace");
    const fragmentUrl = link.dataset.calendarFragmentUrl;
    if (!panel || !fragmentUrl) return;
    event.preventDefault();
    if (link.dataset.calendarAction === "week" || link.dataset.calendarAction === "month") {
      localStorage.setItem(viewStorageKey, link.dataset.calendarAction);
    }
    load(panel, fragmentUrl, link.href, link.dataset.calendarAction || "today", true);
  });

  window.addEventListener("popstate", () => {
    const panel = document.querySelector(".calendar-workspace");
    if (!panel) return;
    const url = new URL(location.href);
    const fragmentUrl = `${panel.dataset.calendarEndpoint}${url.search}`;
    load(panel, fragmentUrl, null, "today", false);
  });

  document.addEventListener("DOMContentLoaded", () => {
    const panel = document.querySelector(".calendar-workspace");
    if (!panel) return;
    const mobile = window.matchMedia("(max-width: 768px)").matches;
    const query = new URL(location.href).searchParams;
    const preferred = mobile ? "month" : (localStorage.getItem(viewStorageKey) || "week");
    if (query.has("view") && !mobile) return;
    if (panel.dataset.calendarMode === preferred) return;
    const link = panel.querySelector(`[data-calendar-action="${preferred}"]`);
    if (!link?.dataset.calendarFragmentUrl) return;
    load(panel, link.dataset.calendarFragmentUrl, null, preferred, false);
    history.replaceState({ calendar: true }, "", link.href);
  });
})();
