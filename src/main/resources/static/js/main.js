document.addEventListener("DOMContentLoaded", function() {
    const sidebarCollapse = document.getElementById('sidebarCollapse');
    const sidebar = document.getElementById('sidebar');

    if(sidebarCollapse) {
        sidebarCollapse.addEventListener('click', function() {
            sidebar.classList.toggle('active');
        });
    }
});

document.addEventListener("DOMContentLoaded", function() {
    const btn = document.getElementById('sidebarCollapse');
    if(btn) btn.addEventListener('click', function() { document.getElementById('sidebar').classList.toggle('active'); });
});