$(document).ready(function () {
    // Support both personTable and groupTable
    let table;
    if ($('#personTable').length) {
        table = $('#personTable').DataTable({
            columnDefs: [
                { targets: [3, 4, 6, 9], visible: false } // Hide Email (3), KSM (4), SID (6), Import/Export (9) by default
                // NOTE: index-based. Order: ID#, UID, Name, Email, KSM, PFP, SID, Mentor, Action, Import/Export.
            ]
        });
    } else if ($('#groupTable').length) {
        table = $('#groupTable').DataTable({
            columnDefs: [
                { targets: [6], visible: false } // Hide Import/Export (index 6) by default
                // NOTE: index-based, so inserting a column into group/read.html shifts this.
                // Order: ID#, Name, Period, Members, Mentors, Action, Import/Export.
            ]
        });
    }

    if (table) {
        // Toggle column visibility and update button styles
        $('.toggle-column').on('click', function () {
            const column = table.column($(this).attr('data-column'));
            const isVisible = column.visible();
            column.visible(!isVisible);

            // Update button styles
            $(this).toggleClass('active', !isVisible);
            $(this).toggleClass('inactive', isVisible);
        });
    }
});