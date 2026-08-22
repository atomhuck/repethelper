(() => {
  const mobile = window.matchMedia("(max-width: 768px)");
  const initialise = () => {
    if (!mobile.matches) return;
    document.querySelectorAll(".calendar-workspace.is-month").forEach(workspace => {
      const panel = workspace.querySelector(".calendar-panel");
      if (!panel) return;
      if (panel.dataset.mobileCalendarReady) return;
      const agendaTemplate = workspace.querySelector("[data-calendar-agenda-template]");
      if (!agendaTemplate?.content) return;
      panel.dataset.mobileCalendarReady = "true";
      const agenda = document.createElement("section");
      agenda.className = "mobile-day-agenda";
      agenda.setAttribute("aria-live", "polite");
      agenda.appendChild(agendaTemplate.content.cloneNode(true));
      panel.appendChild(agenda);
    });
  };
  document.addEventListener("DOMContentLoaded", initialise);
  document.addEventListener("calendar:updated", initialise);
  document.addEventListener("DOMContentLoaded", () => {
    const openNewLesson = (scroll = true) => {
      if (location.pathname !== "/teacher" || location.hash !== "#new-lesson") return;
      const panel = document.getElementById("new-lesson");
      if (!panel) return;
      panel.setAttribute("open", "");
      if (scroll) requestAnimationFrame(() => panel.scrollIntoView({ block: "start", behavior: "smooth" }));
    };
    openNewLesson(false);
    window.addEventListener("hashchange", () => openNewLesson());
    document.addEventListener("click", event => {
      const add = event.target.closest(".mobile-add");
      if (!add || location.pathname !== "/teacher") return;
      if (location.hash === "#new-lesson") {
        event.preventDefault();
        openNewLesson();
      }
    });
    const updateStudentNav = () => {
      if (location.pathname !== "/student") return;
      const nav = document.querySelector(".mobile-nav");
      const history = nav?.querySelector('a[href*="#history"]');
      const lessons = nav?.querySelector('a[href="/student"]');
      if (!history || !lessons) return;
      const historySelected = location.hash === "#history";
      history.classList.toggle("active", historySelected);
      lessons.classList.toggle("active", !historySelected);
    };
    updateStudentNav();
    window.addEventListener("hashchange", updateStudentNav);
  });
  mobile.addEventListener?.("change", initialise);
})();
