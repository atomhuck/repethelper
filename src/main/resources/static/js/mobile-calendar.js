(() => {
  const mobile = window.matchMedia("(max-width: 640px)");
  const initialise = () => {
    if (!mobile.matches) return;
    document.querySelectorAll(".calendar-workspace.is-month").forEach(workspace => {
      const panel = workspace.querySelector(".calendar-panel");
      if (!panel) return;
      if (panel.dataset.mobileCalendarReady) return;
      const days = panel.querySelectorAll(".calendar.days .calendar-day:not(.muted)");
      if (!days.length) return;
      panel.dataset.mobileCalendarReady = "true";
      const agenda = document.createElement("section");
      agenda.className = "mobile-day-agenda";
      agenda.setAttribute("aria-live", "polite");
      panel.appendChild(agenda);
      const title = panel.querySelector("h2")?.textContent || "";
      const nearestLesson = panel.closest("main")?.querySelector(".side-panel .upcoming-item, .workspace-agenda .upcoming-item");
      const show = (day, initial = false) => {
        days.forEach(item => item.classList.toggle("selected", item === day));
        const number = day.querySelector(".day-number")?.textContent || "";
        const events = [...day.querySelectorAll(".calendar-event")];
        const isToday = day.classList.contains("today");
        agenda.innerHTML = `<p class="eyebrow">${isToday ? "Сегодня" : "Выбранный день"}</p><h3>${number} ${title}</h3>`;
        if (!events.length) {
          agenda.insertAdjacentHTML("beforeend", `<p class="mobile-day-empty">${isToday ? "На сегодняшний день никаких занятий не запланировано." : "На этот день занятий не запланировано."}</p>`);
        } else {
          const list = document.createElement("div");
          list.className = "mobile-day-list";
          events.forEach(event => list.appendChild(event.cloneNode(true)));
          agenda.appendChild(list);
        }
        if (initial && isToday) {
          const next = document.createElement("div");
          next.className = "mobile-next-lesson";
          next.innerHTML = "<p class=\"eyebrow\">Ближайшее занятие</p>";
          if (nearestLesson) next.appendChild(nearestLesson.cloneNode(true));
          else next.insertAdjacentHTML("beforeend", "<p>Ближайших занятий пока нет.</p>");
          agenda.appendChild(next);
        }
      };
      days.forEach(day => {
        day.addEventListener("click", event => {
          if (event.target.closest("a")) return;
          day.querySelector(".day-number")?.click();
        });
      });
      const today = [...days].find(day => day.classList.contains("today"));
      show(today || [...days].find(day => day.querySelector(".calendar-event")) || days[0], Boolean(today));
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
