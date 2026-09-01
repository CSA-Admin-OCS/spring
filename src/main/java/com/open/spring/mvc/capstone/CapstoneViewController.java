package com.open.spring.mvc.capstone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** Admin DB page for attaching mentors to capstone projects. */
@Controller
@RequestMapping("/mvc/capstone")
public class CapstoneViewController {

    @Autowired
    private CapstoneProjectJpaRepository capstoneRepository;

    @GetMapping("/read")
    @Transactional(readOnly = true)
    public String read(Model model) {
        model.addAttribute("list", capstoneRepository.findAllByOrderByTitleAsc());
        return "capstone/read";
    }
}
