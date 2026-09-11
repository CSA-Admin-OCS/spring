package com.open.spring.mvc.directory;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/mvc/directory")
public class DirectoryAccountController {
    private final DirectoryAccountRepository repository;

    public DirectoryAccountController(DirectoryAccountRepository repository) {
        this.repository = repository;
    }

    @InitBinder("account")
    void bindEditableFields(WebDataBinder binder) {
        // A submitted form cannot choose the primary key or creation time.
        binder.setAllowedFields("name", "email", "school", "studentID",
                "githubUsername", "accountType");
    }

    @ModelAttribute("accountTypes")
    AccountType[] accountTypes() {
        return AccountType.values();
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("accounts", repository.findAll(
                org.springframework.data.domain.Sort.by("id")));
        return "directory/list";
    }

    @GetMapping("/{id}")
    String detail(@PathVariable Long id, Model model) {
        model.addAttribute("account", requireAccount(id));
        return "directory/detail";
    }

    @GetMapping("/new")
    String createForm(Model model) {
        return form(new DirectoryAccount(), null, model);
    }

    @PostMapping
    String create(@Valid @ModelAttribute("account") DirectoryAccount account,
                  BindingResult errors, Model model,
                  RedirectAttributes redirect) {
        if (errors.hasErrors()) return form(account, null, model);
        DirectoryAccount saved = repository.save(account);
        redirect.addFlashAttribute("message", "Account created.");
        return "redirect:/mvc/directory/" + saved.getId();
    }

    @GetMapping("/{id}/edit")
    String editForm(@PathVariable Long id, Model model) {
        return form(requireAccount(id), id, model);
    }

    @PostMapping("/{id}")
    String update(@PathVariable Long id,
                  @Valid @ModelAttribute("account") DirectoryAccount submitted,
                  BindingResult errors, Model model,
                  RedirectAttributes redirect) {
        DirectoryAccount existing = requireAccount(id);
        if (errors.hasErrors()) return form(submitted, id, model);
        copyEditableFields(submitted, existing);
        repository.save(existing);
        redirect.addFlashAttribute("message", "Account updated.");
        return "redirect:/mvc/directory/" + id;
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable Long id, RedirectAttributes redirect) {
        repository.delete(requireAccount(id));
        redirect.addFlashAttribute("message", "Account deleted.");
        return "redirect:/mvc/directory";
    }

    private DirectoryAccount requireAccount(Long id) {
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Directory account not found"));
    }

    private String form(DirectoryAccount account, Long editingId, Model model) {
        model.addAttribute("account", account);
        model.addAttribute("editingId", editingId);
        return "directory/form";
    }

    private void copyEditableFields(DirectoryAccount source,
                                    DirectoryAccount target) {
        target.setName(source.getName());
        target.setEmail(source.getEmail());
        target.setSchool(source.getSchool());
        target.setStudentID(source.getStudentID());
        target.setGithubUsername(source.getGithubUsername());
        target.setAccountType(source.getAccountType());
    }
}

