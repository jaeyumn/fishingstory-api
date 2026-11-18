-- Test only: a later migration may not deactivate the original of an active cultivar.
UPDATE fishingstory.fish_species SET active = FALSE WHERE species_id = 1003;
